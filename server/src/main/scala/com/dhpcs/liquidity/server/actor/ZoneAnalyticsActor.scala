package com.dhpcs.liquidity.server.actor

import akka.pattern.pipe
import akka.persistence.query.scaladsl.{CurrentEventsByPersistenceIdQuery, EventsByPersistenceIdQuery, ReadJournal}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{KillSwitches, Materializer}
import akka.typed.cluster.ActorRefResolver
import akka.typed.cluster.sharding.{EntityTypeKey, ShardingMessageExtractor}
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.adapter._
import akka.typed.{ActorRef, Behavior, PostStop}
import cats.effect.IO
import cats.syntax.applicative._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.persistence.zone._
import com.dhpcs.liquidity.server.SqlAnalyticsStore
import doobie._
import doobie.implicits._

import scala.concurrent.ExecutionContext

object ZoneAnalyticsActor {

  final val ShardingTypeName = EntityTypeKey[ZoneAnalyticsMessage]("zone-analytics")

  private final val MaxNumberOfShards = 10

  val messageExtractor: ShardingMessageExtractor[ZoneAnalyticsMessage, ZoneAnalyticsMessage] =
    ShardingMessageExtractor.noEnvelope(
      MaxNumberOfShards, {
        // This has to be part of ZoneAnalyticsMessage because the akka-typed sharding API requires that the hand-off
        // stop-message is a subtype of the ShardingMessageExtractor Envelope type. Of course, hand-off stop-messages
        // are sent directly to the entity to be stopped, so this extractor won't actually encounter them.
        case StopZoneAnalytics             => throw new IllegalArgumentException("Received StopZoneAnalytics")
        case StartZoneAnalytics(_, zoneId) => zoneId.id
      }
    )

  sealed abstract class ZoneAnalyticsMessage
  case object StopZoneAnalytics                                                extends ZoneAnalyticsMessage
  final case class StartZoneAnalytics(replyTo: ActorRef[Unit], zoneId: ZoneId) extends ZoneAnalyticsMessage

  def shardingBehavior(readJournal: ReadJournal with CurrentEventsByPersistenceIdQuery with EventsByPersistenceIdQuery,
                       transactor: Transactor[IO],
                       streamFailureHandler: PartialFunction[Throwable, Unit])(
      implicit ec: ExecutionContext,
      mat: Materializer): Behavior[ZoneAnalyticsMessage] =
    Actor.deferred { context =>
      val killSwitch = KillSwitches.shared("zone-events")
      Actor.immutable[ZoneAnalyticsMessage]((_, message) =>
        message match {
          case StopZoneAnalytics =>
            Actor.stopped

          case StartZoneAnalytics(replyTo, zoneId) =>
            implicit val actorRefResolver: ActorRefResolver = ActorRefResolver(context.system)
            val previousSequenceNumber = SqlAnalyticsStore.JournalSequenceNumberStore
              .retrieve(zoneId)
              .map(_.getOrElse(0L))
              .transact(transactor)
              .unsafeRunSync()
            val currentSequenceNumber = readJournal
              .currentEventsByPersistenceId(zoneId.persistenceId, previousSequenceNumber + 1, Long.MaxValue)
              .map(_.event.asInstanceOf[ZoneEventEnvelope])
              .runFold(previousSequenceNumber)(applyEventAndUpdateStore(transactor, zoneId))
            currentSequenceNumber.map(_ => ()).pipeTo(replyTo.toUntyped)
            val done = Source
              .fromFuture(currentSequenceNumber)
              .via(killSwitch.flow)
              .flatMapConcat(
                currentSequenceNumber =>
                  readJournal
                    .eventsByPersistenceId(zoneId.persistenceId, currentSequenceNumber + 1, Long.MaxValue)
                    .map(_.event.asInstanceOf[ZoneEventEnvelope])
                    .fold(currentSequenceNumber)(applyEventAndUpdateStore(transactor, zoneId)))
              .toMat(Sink.ignore)(Keep.right)
              .run()
            done.failed.foreach(t => streamFailureHandler.applyOrElse(t, throw _: Throwable))
            Actor.same
      }) onSignal {
        case (_, PostStop) =>
          killSwitch.shutdown()
          Actor.same
      }
    }

  private def applyEventAndUpdateStore(transactor: Transactor[IO], zoneId: ZoneId)(
      implicit actorRefResolver: ActorRefResolver): (Long, ZoneEventEnvelope) => (Long) =
    (previousSequenceNumber, zoneEventEnvelope) => {
      (for {
        _ <- zoneEventEnvelope.zoneEvent match {
          case EmptyZoneEvent =>
            ().pure[ConnectionIO]

          case ZoneCreatedEvent(zone) =>
            SqlAnalyticsStore.ZoneStore.insert(zone)

          case ClientJoinedEvent(maybeActorRef) =>
            maybeActorRef match {
              case None =>
                ().pure[ConnectionIO]

              case Some(actorRef) =>
                SqlAnalyticsStore.ClientSessionsStore.insert(zoneId,
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
                for (previousSessionId <- SqlAnalyticsStore.ClientSessionsStore.retrieve(zoneId, actorRef))
                  yield
                    previousSessionId match {
                      case None =>
                        ().pure[ConnectionIO]

                      case Some(sessionId) =>
                        SqlAnalyticsStore.ClientSessionsStore.update(sessionId, quit = zoneEventEnvelope.timestamp)
                    }
            }

          case ZoneNameChangedEvent(name) =>
            SqlAnalyticsStore.ZoneNameChangeStore.insert(zoneId, changed = zoneEventEnvelope.timestamp, name)

          case MemberCreatedEvent(member) =>
            SqlAnalyticsStore.MembersStore.insert(zoneId, member, created = zoneEventEnvelope.timestamp)

          case MemberUpdatedEvent(member) =>
            SqlAnalyticsStore.MemberUpdatesStore.insert(zoneId, member, updated = zoneEventEnvelope.timestamp)

          case AccountCreatedEvent(account) =>
            SqlAnalyticsStore.AccountsStore.insert(zoneId,
                                                   account,
                                                   created = zoneEventEnvelope.timestamp,
                                                   balance = BigDecimal(0))

          case AccountUpdatedEvent(_, account) =>
            SqlAnalyticsStore.AccountUpdatesStore.insert(zoneId, account, updated = zoneEventEnvelope.timestamp)

          case TransactionAddedEvent(transaction) =>
            for {
              _                     <- SqlAnalyticsStore.TransactionsStore.insert(zoneId, transaction)
              previousSourceBalance <- SqlAnalyticsStore.AccountsStore.retrieveBalance(zoneId, transaction.from)
              _ <- SqlAnalyticsStore.AccountsStore.update(zoneId,
                                                          transaction.from,
                                                          previousSourceBalance - transaction.value)
              previousDestinationBalance <- SqlAnalyticsStore.AccountsStore.retrieveBalance(zoneId, transaction.to)
              _ <- SqlAnalyticsStore.AccountsStore.update(zoneId,
                                                          transaction.to,
                                                          previousDestinationBalance + transaction.value)
            } yield ()
        }
        _ <- SqlAnalyticsStore.ZoneStore.update(zoneId, modified = zoneEventEnvelope.timestamp)
        updatedSequenceNumber <- zoneEventEnvelope.zoneEvent match {
          case ZoneCreatedEvent(_) =>
            SqlAnalyticsStore.JournalSequenceNumberStore.insert(zoneId, previousSequenceNumber + 1)

          case _ =>
            SqlAnalyticsStore.JournalSequenceNumberStore.update(zoneId, previousSequenceNumber + 1)
        }
      } yield updatedSequenceNumber).transact(transactor).unsafeRunSync()
    }

}
