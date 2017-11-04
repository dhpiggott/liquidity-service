package com.dhpcs.liquidity.server.actor

import akka.event.Logging
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.TimeBasedUUID
import akka.stream.scaladsl.{Keep, Sink}
import akka.stream.{KillSwitches, Materializer}
import akka.typed.cluster.ActorRefResolver
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.adapter._
import akka.typed.{Behavior, PostStop}
import cats.effect.IO
import cats.syntax.applicative._
import com.dhpcs.liquidity.model.ZoneId
import com.dhpcs.liquidity.persistence.EventTags
import com.dhpcs.liquidity.persistence.zone._
import com.dhpcs.liquidity.server.SqlAnalyticsStore._
import doobie._
import doobie.implicits._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object ZoneAnalyticsActor {

  sealed abstract class ZoneAnalyticsMessage
  case object StopZoneAnalytics extends ZoneAnalyticsMessage

  def singletonBehavior(readJournal: CassandraReadJournal,
                        transactor: Transactor[IO],
                        streamFailureHandler: PartialFunction[Throwable, Unit])(
      implicit ec: ExecutionContext,
      mat: Materializer): Behavior[ZoneAnalyticsMessage] =
    Actor.deferred { context =>
      val log                                         = Logging(context.system.toUntyped, context.self.toUntyped)
      implicit val actorRefResolver: ActorRefResolver = ActorRefResolver(context.system)
      val offset = for {
        maybePreviousOffset <- TagOffsetsStore.retrieve(EventTags.ZoneEventTag)
        offset <- maybePreviousOffset match {
          case None =>
            val firstOffset = TimeBasedUUID(readJournal.firstOffset)
            for (_ <- TagOffsetsStore.insert(EventTags.ZoneEventTag, firstOffset)) yield firstOffset
          case Some(previousOffset) =>
            previousOffset.pure[ConnectionIO]
        }
      } yield offset
      val (killSwitch, done) =
        readJournal
          .eventsByTag(EventTags.ZoneEventTag, offset.transact(transactor).unsafeRunSync())
          .viaMat(KillSwitches.single)(Keep.right)
          .map { eventEnvelope =>
            val zoneId            = ZoneId.fromPersistenceId(eventEnvelope.persistenceId)
            val zoneEventEnvelope = eventEnvelope.event.asInstanceOf[ZoneEventEnvelope]
            val offset            = eventEnvelope.offset.asInstanceOf[TimeBasedUUID]
            val update = for {
              _ <- projectEvent(zoneId, zoneEventEnvelope)
              _ <- TagOffsetsStore.update(EventTags.ZoneEventTag, offset)
            } yield ()
            update.transact(transactor).unsafeRunSync()
          }
          .zipWithIndex
          .groupedWithin(n = 1000, d = 30.seconds)
          .map { group =>
            val (_, index) = group.last
            log.info(s"Projected ${group.size} zone events (total: ${index + 1})")
          }
          .toMat(Sink.ignore)(Keep.both)
          .run()
      done.failed.foreach(t => streamFailureHandler.applyOrElse(t, throw _: Throwable))
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

  private[this] def projectEvent(zoneId: ZoneId, zoneEventEnvelope: ZoneEventEnvelope)(
      implicit actorRefResolver: ActorRefResolver): ConnectionIO[Unit] =
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
            case None => ().pure[ConnectionIO]

            case Some(actorRef) =>
              for (previousSessionId <- ClientSessionsStore.retrieve(zoneId, actorRef))
                yield
                  previousSessionId match {
                    case None            => ().pure[ConnectionIO]
                    case Some(sessionId) => ClientSessionsStore.update(sessionId, quit = zoneEventEnvelope.timestamp)
                  }
          }

        case ZoneNameChangedEvent(name) =>
          ZoneNameChangeStore.insert(zoneId, name, changed = zoneEventEnvelope.timestamp)

        case MemberCreatedEvent(member) =>
          MembersStore.insert(zoneId, member, created = zoneEventEnvelope.timestamp)

        case MemberUpdatedEvent(member) =>
          MemberUpdatesStore.insert(zoneId, member, updated = zoneEventEnvelope.timestamp)

        case AccountCreatedEvent(account) =>
          AccountsStore.insert(zoneId, account, created = zoneEventEnvelope.timestamp, balance = BigDecimal(0))

        case AccountUpdatedEvent(_, account) =>
          AccountUpdatesStore.insert(zoneId, account, updated = zoneEventEnvelope.timestamp)

        case TransactionAddedEvent(transaction) =>
          for {
            _                  <- TransactionsStore.insert(zoneId, transaction)
            sourceBalance      <- AccountsStore.retrieveBalance(zoneId, transaction.from)
            _                  <- AccountsStore.update(zoneId, transaction.from, sourceBalance - transaction.value)
            destinationBalance <- AccountsStore.retrieveBalance(zoneId, transaction.to)
            _                  <- AccountsStore.update(zoneId, transaction.to, destinationBalance + transaction.value)
          } yield ()
      }
      _ <- ZoneStore.update(zoneId, modified = zoneEventEnvelope.timestamp)
    } yield ()

}
