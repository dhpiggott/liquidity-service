package com.dhpcs.liquidity.server.actor

import akka.event.Logging
import akka.persistence.query.Sequence
import akka.persistence.query.scaladsl.{EventsByTagQuery, ReadJournal}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{KillSwitches, Materializer}
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.adapter._
import akka.typed.{Behavior, PostStop}
import cats.effect.IO
import cats.syntax.applicative._
import com.dhpcs.liquidity.model.ZoneId
import com.dhpcs.liquidity.persistence.EventTags
import com.dhpcs.liquidity.persistence.zone._
import com.dhpcs.liquidity.server.LiquidityServer.TransactIoToFuture
import com.dhpcs.liquidity.server.SqlAnalyticsStore._
import doobie._
import doobie.implicits._

import scala.concurrent.duration._

object ZoneAnalyticsActor {

  sealed abstract class ZoneAnalyticsMessage
  case object StopZoneAnalytics extends ZoneAnalyticsMessage

  def singletonBehavior(readJournal: ReadJournal with EventsByTagQuery,
                        analyticsTransactor: Transactor[IO],
                        transactIoToFuture: TransactIoToFuture)(
      implicit mat: Materializer): Behavior[ZoneAnalyticsMessage] =
    Actor.deferred { context =>
      val log = Logging(context.system.toUntyped, context.self.toUntyped)
      val offset = transactIoToFuture(analyticsTransactor)(for {
        maybePreviousOffset <- TagOffsetsStore.retrieve(EventTags.ZoneEventTag)
        offset <- maybePreviousOffset match {
          case None =>
            val firstOffset = Sequence(0)
            for (_ <- TagOffsetsStore.insert(EventTags.ZoneEventTag,
                                             firstOffset)) yield firstOffset
          case Some(previousOffset) =>
            previousOffset.pure[ConnectionIO]
        }
      } yield offset)
      val (killSwitch, done) =
        Source
          .fromFuture(offset)
          .flatMapConcat(readJournal.eventsByTag(EventTags.ZoneEventTag, _))
          .viaMat(KillSwitches.single)(Keep.right)
          .mapAsync(1) { eventEnvelope =>
            val zoneId = ZoneId.fromPersistenceId(eventEnvelope.persistenceId)
            val zoneEventEnvelope =
              eventEnvelope.event.asInstanceOf[ZoneEventEnvelope]
            val offset = eventEnvelope.offset.asInstanceOf[Sequence]
            transactIoToFuture(analyticsTransactor)(for {
              _ <- projectEvent(zoneId, zoneEventEnvelope)
              _ <- TagOffsetsStore.update(EventTags.ZoneEventTag, offset)
            } yield offset)
          }
          .zipWithIndex
          .groupedWithin(n = 1000, d = 30.seconds)
          .map { group =>
            val (offset, index) = group.last
            log.info(
              s"Projected ${group.size} zone events (total: ${index + 1}, offset: ${offset.value})")
          }
          .toMat(Sink.ignore)(Keep.both)
          .run()
      done.failed.foreach { t =>
        log.error(t, "Analytics stream failure")
        context.self ! StopZoneAnalytics
      } { context.executionContext }
      Actor.immutable[ZoneAnalyticsMessage]((_, message) =>
        message match {
          case StopZoneAnalytics =>
            Actor.stopped
      }) onSignal {
        case (_, PostStop) =>
          killSwitch.shutdown()
          Actor.same
      }
    }

  private[this] def projectEvent(
      zoneId: ZoneId,
      zoneEventEnvelope: ZoneEventEnvelope): ConnectionIO[Unit] =
    for {
      _ <- zoneEventEnvelope.zoneEvent match {
        case EmptyZoneEvent =>
          ().pure[ConnectionIO]

        case ZoneCreatedEvent(zone) =>
          ZoneStore.insert(zone)

        case ClientJoinedEvent(maybeActorRef) =>
          maybeActorRef match {
            case None =>
              ().pure[ConnectionIO]

            case Some(actorRef) =>
              ClientSessionsStore.insert(zoneId,
                                         zoneEventEnvelope.remoteAddress,
                                         actorRef,
                                         zoneEventEnvelope.publicKey,
                                         joined = zoneEventEnvelope.timestamp)
          }

        case ClientQuitEvent(maybeActorRef) =>
          maybeActorRef match {
            case None =>
              ().pure[ConnectionIO]

            case Some(actorRef) =>
              for {
                previousSessionId <- ClientSessionsStore.retrieve(zoneId,
                                                                  actorRef)
                _ <- ClientSessionsStore.update(previousSessionId,
                                                quit =
                                                  zoneEventEnvelope.timestamp)
              } yield ()
          }

        case ZoneNameChangedEvent(name) =>
          ZoneNameChangeStore.insert(zoneId,
                                     name,
                                     changed = zoneEventEnvelope.timestamp)

        case MemberCreatedEvent(member) =>
          MembersStore.insert(zoneId,
                              member,
                              created = zoneEventEnvelope.timestamp)

        case MemberUpdatedEvent(member) =>
          MemberUpdatesStore.insert(zoneId,
                                    member,
                                    updated = zoneEventEnvelope.timestamp)

        case AccountCreatedEvent(account) =>
          AccountsStore.insert(zoneId,
                               account,
                               created = zoneEventEnvelope.timestamp,
                               balance = BigDecimal(0))

        case AccountUpdatedEvent(_, account) =>
          AccountUpdatesStore.insert(zoneId,
                                     account,
                                     updated = zoneEventEnvelope.timestamp)

        case TransactionAddedEvent(transaction) =>
          for {
            _ <- TransactionsStore.insert(zoneId, transaction)
            sourceBalance <- AccountsStore.retrieveBalance(zoneId,
                                                           transaction.from)
            _ <- AccountsStore.update(zoneId,
                                      transaction.from,
                                      sourceBalance - transaction.value)
            destinationBalance <- AccountsStore.retrieveBalance(zoneId,
                                                                transaction.to)
            _ <- AccountsStore.update(zoneId,
                                      transaction.to,
                                      destinationBalance + transaction.value)
          } yield ()
      }
      _ <- ZoneStore.update(zoneId, modified = zoneEventEnvelope.timestamp)
    } yield ()

}
