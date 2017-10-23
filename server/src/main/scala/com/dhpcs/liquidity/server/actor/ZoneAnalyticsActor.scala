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
import cats.syntax.apply._
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
            val (previousSequenceNumber, previousBalances, previousClientSessions) =
              (SqlAnalyticsStore.JournalSequenceNumberStore.retrieve(zoneId).map(_.getOrElse(0L)),
               SqlAnalyticsStore.AccountsStore.retrieveAllBalances(zoneId),
               SqlAnalyticsStore.ClientSessionsStore.retrieveAll(zoneId)).tupled.transact(transactor).unsafeRunSync()
            val currentJournalState = for {
              (currentBalances, currentClientSessions, currentSequenceNumber) <- readJournal
                .currentEventsByPersistenceId(zoneId.persistenceId, previousSequenceNumber + 1, Long.MaxValue)
                .map(_.event.asInstanceOf[ZoneEventEnvelope])
                // TODO: Be stateless
                .runFold((previousBalances, previousClientSessions, previousSequenceNumber))(
                  applyEventAndUpdateStore(transactor, zoneId))
            } yield (currentBalances, currentClientSessions, currentSequenceNumber)
            currentJournalState.map(_ => ()).pipeTo(replyTo.toUntyped)
            val done = Source
              .fromFuture(currentJournalState)
              .via(killSwitch.flow)
              .flatMapConcat {
                case (currentBalances, currentClientSessions, currentSequenceNumber) =>
                  readJournal
                    .eventsByPersistenceId(zoneId.persistenceId, currentSequenceNumber + 1, Long.MaxValue)
                    .map(_.event.asInstanceOf[ZoneEventEnvelope])
                    // TODO: Be stateless
                    .fold((currentBalances, currentClientSessions, currentSequenceNumber))(
                      applyEventAndUpdateStore(transactor, zoneId))
              }
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
      implicit actorRefResolver: ActorRefResolver)
    : ((Map[AccountId, BigDecimal], Map[ActorRef[Nothing], Long], Long),
       ZoneEventEnvelope) => (Map[AccountId, BigDecimal], Map[ActorRef[Nothing], Long], Long) =
    (previousBalancesClientSessionsAndSequenceNumber, zoneEventEnvelope) => {
      val (previousBalances, previousClientSessions, previousSequenceNumber) =
        previousBalancesClientSessionsAndSequenceNumber
      (for {
        updatedBalancesAndClientSessions <- zoneEventEnvelope.zoneEvent match {
          case EmptyZoneEvent =>
            (previousBalances, previousClientSessions).pure[ConnectionIO]

          case ZoneCreatedEvent(zone) =>
            for (_ <- SqlAnalyticsStore.ZoneStore.insert(zone))
              yield (previousBalances ++ zone.accounts.mapValues(_ => BigDecimal(0)), previousClientSessions)

          case ClientJoinedEvent(maybeActorRef) =>
            maybeActorRef match {
              case None =>
                (previousBalances, previousClientSessions).pure[ConnectionIO]

              case Some(actorRef) =>
                for (sessionId <- SqlAnalyticsStore.ClientSessionsStore.insert(zoneId,
                                                                               zoneEventEnvelope.remoteAddress,
                                                                               actorRef,
                                                                               zoneEventEnvelope.publicKey,
                                                                               joined = zoneEventEnvelope.timestamp))
                  yield (previousBalances, previousClientSessions + (actorRef -> sessionId))
            }

          case ClientQuitEvent(maybeActorRef) =>
            maybeActorRef match {
              case None =>
                (previousBalances, previousClientSessions).pure[ConnectionIO]

              case Some(actorRef) =>
                previousClientSessions.get(actorRef) match {
                  case None =>
                    (previousBalances, previousClientSessions).pure[ConnectionIO]

                  case Some(sessionId) =>
                    for (_ <- SqlAnalyticsStore.ClientSessionsStore.update(sessionId,
                                                                           quit = zoneEventEnvelope.timestamp))
                      yield (previousBalances, previousClientSessions - actorRef)
                }
            }

          case ZoneNameChangedEvent(name) =>
            for (_ <- SqlAnalyticsStore.ZoneNameChangeStore.insert(zoneId, changed = zoneEventEnvelope.timestamp, name))
              yield (previousBalances, previousClientSessions)

          case MemberCreatedEvent(member) =>
            for (_ <- SqlAnalyticsStore.MembersStore.insert(zoneId, member, created = zoneEventEnvelope.timestamp))
              yield (previousBalances, previousClientSessions)

          case MemberUpdatedEvent(member) =>
            for (_ <- SqlAnalyticsStore.MemberUpdatesStore.insert(zoneId,
                                                                  member,
                                                                  updated = zoneEventEnvelope.timestamp))
              yield (previousBalances, previousClientSessions)

          case AccountCreatedEvent(account) =>
            for (_ <- SqlAnalyticsStore.AccountsStore.insert(zoneId,
                                                             account,
                                                             created = zoneEventEnvelope.timestamp,
                                                             balance = BigDecimal(0)))
              yield (previousBalances + (account.id -> BigDecimal(0)), previousClientSessions)

          case AccountUpdatedEvent(_, account) =>
            for (_ <- SqlAnalyticsStore.AccountUpdatesStore.insert(zoneId,
                                                                   account,
                                                                   updated = zoneEventEnvelope.timestamp))
              yield (previousBalances, previousClientSessions)

          case TransactionAddedEvent(transaction) =>
            val updatedSourceBalance      = previousBalances(transaction.from) - transaction.value
            val updatedDestinationBalance = previousBalances(transaction.to) + transaction.value
            for {
              _ <- SqlAnalyticsStore.TransactionsStore.insert(zoneId, transaction)
              _ <- SqlAnalyticsStore.AccountsStore.update(zoneId, transaction.from, updatedSourceBalance)
              _ <- SqlAnalyticsStore.AccountsStore.update(zoneId, transaction.to, updatedDestinationBalance)
            } yield
              (previousBalances + (transaction.from -> updatedSourceBalance) + (transaction.to -> updatedDestinationBalance),
               previousClientSessions)
        }
        (updatedBalances, updatedClientSessions) = updatedBalancesAndClientSessions
        _ <- SqlAnalyticsStore.ZoneStore.update(zoneId, modified = zoneEventEnvelope.timestamp)
        updatedSequenceNumber <- zoneEventEnvelope.zoneEvent match {
          case ZoneCreatedEvent(_) =>
            SqlAnalyticsStore.JournalSequenceNumberStore.insert(zoneId, previousSequenceNumber + 1)

          case _ =>
            SqlAnalyticsStore.JournalSequenceNumberStore.update(zoneId, previousSequenceNumber + 1)
        }
      } yield (updatedBalances, updatedClientSessions, updatedSequenceNumber)).transact(transactor).unsafeRunSync()
    }

}
