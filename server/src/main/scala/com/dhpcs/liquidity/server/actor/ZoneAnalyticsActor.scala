package com.dhpcs.liquidity.server.actor

import java.time.Instant

import akka.pattern.pipe
import akka.persistence.query.EventEnvelope
import akka.persistence.query.scaladsl.{CurrentEventsByPersistenceIdQuery, EventsByPersistenceIdQuery, ReadJournal}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{KillSwitches, Materializer}
import akka.typed.cluster.ActorRefResolver
import akka.typed.cluster.sharding.{EntityTypeKey, ShardingMessageExtractor}
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.adapter._
import akka.typed.{ActorRef, Behavior, PostStop}
import cats.Cartesian
import cats.instances.option._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.persistence.zone._
import com.dhpcs.liquidity.server.CassandraAnalyticsStore

import scala.concurrent.{ExecutionContext, Future}

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
                       futureAnalyticsStore: Future[CassandraAnalyticsStore],
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
            val currentJournalState = for {
              analyticsStore         <- futureAnalyticsStore
              previousSequenceNumber <- analyticsStore.journalSequenceNumberStore.retrieve(zoneId)
              previousZone <- previousSequenceNumber match {
                case 0L => Future.successful(null)
                case _  => analyticsStore.zoneStore.retrieve(zoneId)
              }
              previousBalances <- analyticsStore.balanceStore.retrieve(zoneId)
              previousClients <- analyticsStore.clientStore
                .retrieve(zoneId)(context.executionContext, ActorRefResolver(context.system))
              (currentSequenceNumber, currentZone, currentBalances, currentClients) <- readJournal
                .currentEventsByPersistenceId(zoneId.persistenceId, previousSequenceNumber, Long.MaxValue)
                .runFoldAsync((previousSequenceNumber, previousZone, previousBalances, previousClients))(
                  updateStoreAndApplyEvent(analyticsStore, zoneId))
            } yield (analyticsStore, currentSequenceNumber, currentZone, currentBalances, currentClients)
            currentJournalState.map(_ => ()).pipeTo(replyTo.toUntyped)
            val done = Source
              .fromFuture(currentJournalState)
              .via(killSwitch.flow)
              .flatMapConcat {
                case (analyticsStore, currentSequenceNumber, currentZone, currentBalances, currentClients) =>
                  readJournal
                    .eventsByPersistenceId(zoneId.persistenceId, currentSequenceNumber, Long.MaxValue)
                    .foldAsync((currentSequenceNumber, currentZone, currentBalances, currentClients))(
                      updateStoreAndApplyEvent(analyticsStore, zoneId))
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

  private def updateStoreAndApplyEvent(analyticsStore: CassandraAnalyticsStore, zoneId: ZoneId)(
      previousSequenceNumberZoneBalancesAndClients: (Long,
                                                     Zone,
                                                     Map[AccountId, BigDecimal],
                                                     Map[ActorRef[Nothing], (Instant, PublicKey)]),
      envelope: EventEnvelope)(implicit ec: ExecutionContext)
    : Future[(Long, Zone, Map[AccountId, BigDecimal], Map[ActorRef[Nothing], (Instant, PublicKey)])] = {
    val (_, previousZone, previousBalances, previousClients) =
      previousSequenceNumberZoneBalancesAndClients
    val (maybePublicKey, timestamp, zoneEvent) = envelope.event match {
      case zoneEventEnvelope: ZoneEventEnvelope =>
        (zoneEventEnvelope.publicKey, zoneEventEnvelope.timestamp, zoneEventEnvelope.zoneEvent)
    }
    val updatedZone = zoneEvent match {
      case ZoneCreatedEvent(zone) =>
        zone

      case ZoneNameChangedEvent(name) =>
        previousZone.copy(name = name)

      case MemberCreatedEvent(member) =>
        previousZone.copy(members = previousZone.members + (member.id -> member))

      case MemberUpdatedEvent(member) =>
        previousZone.copy(members = previousZone.members + (member.id -> member))

      case AccountCreatedEvent(account) =>
        previousZone.copy(accounts = previousZone.accounts + (account.id -> account))

      case AccountUpdatedEvent(_, account) =>
        previousZone.copy(accounts = previousZone.accounts + (account.id -> account))

      case TransactionAddedEvent(transaction) =>
        previousZone.copy(transactions = previousZone.transactions + (transaction.id -> transaction))

      case _ =>
        previousZone
    }

    val updatedClients = zoneEvent match {
      case ClientJoinedEvent(maybeClientConnection) =>
        Cartesian[Option].product(maybeClientConnection, maybePublicKey) match {
          case None =>
            previousClients
          case Some((clientConnection, publicKey)) =>
            previousClients + (clientConnection -> (timestamp -> publicKey))
        }

      case ClientQuitEvent(maybeClientConnection) =>
        maybeClientConnection match {
          case None =>
            previousClients
          case Some(clientConnection) =>
            previousClients - clientConnection
        }

      case _ =>
        previousClients
    }

    val updatedBalances = zoneEvent match {
      case ZoneCreatedEvent(zone) =>
        previousBalances ++ zone.accounts.mapValues(_ => BigDecimal(0))

      case AccountCreatedEvent(account) =>
        previousBalances + (account.id -> BigDecimal(0))

      case TransactionAddedEvent(transaction) =>
        val updatedSourceBalance      = previousBalances(transaction.from) - transaction.value
        val updatedDestinationBalance = previousBalances(transaction.to) + transaction.value
        previousBalances + (transaction.from -> updatedSourceBalance) + (transaction.to -> updatedDestinationBalance)

      case _ => previousBalances
    }

    for {
      _ <- zoneEvent match {
        case EmptyZoneEvent =>
          Future.successful(())

        case ZoneCreatedEvent(zone) =>
          for {
            _ <- analyticsStore.zoneStore.create(zone)
            _ <- analyticsStore.balanceStore.create(zone, BigDecimal(0), zone.accounts.values)
          } yield ()

        case ClientJoinedEvent(maybeClientConnection) =>
          Cartesian[Option].product(maybeClientConnection, maybePublicKey) match {
            case None =>
              Future.successful(())
            case Some((clientConnection, publicKey)) =>
              analyticsStore.clientStore.createOrUpdate(zoneId,
                                                        publicKey,
                                                        joined = timestamp,
                                                        actorRef = clientConnection)
          }

        case ClientQuitEvent(maybeClientConnection) =>
          maybeClientConnection match {
            case None =>
              Future.successful(())
            case Some(clientConnection) =>
              previousClients.get(clientConnection) match {
                case None =>
                  Future.successful(())
                case Some((joined, publicKey)) =>
                  analyticsStore.clientStore.update(zoneId,
                                                    publicKey,
                                                    joined,
                                                    actorRef = clientConnection,
                                                    quit = timestamp)
              }
          }

        case ZoneNameChangedEvent(name) =>
          analyticsStore.zoneStore.changeName(zoneId, modified = timestamp, name)

        case MemberCreatedEvent(member) =>
          analyticsStore.zoneStore.memberStore.create(zoneId, created = timestamp)(member)

        case MemberUpdatedEvent(member) =>
          for {
            _ <- analyticsStore.zoneStore.memberStore.update(zoneId, modified = timestamp, member)
            updatedAccounts = previousZone.accounts.values.filter(_.ownerMemberIds.contains(member.id))
            _ <- analyticsStore.zoneStore.accountStore.update(previousZone, updatedAccounts)
            updatedTransactions = previousZone.transactions.values.filter(transaction =>
              updatedAccounts.exists(_.id == transaction.from) || updatedAccounts.exists(_.id == transaction.to))
            _ <- analyticsStore.zoneStore.transactionStore.update(previousZone, updatedTransactions)
            _ <- analyticsStore.balanceStore.update(previousZone, updatedAccounts, previousBalances)
          } yield ()

        case AccountCreatedEvent(account) =>
          for {
            _ <- analyticsStore.zoneStore.accountStore.create(previousZone, created = timestamp)(account)
            _ <- analyticsStore.balanceStore.create(previousZone, BigDecimal(0))(account)
          } yield ()

        case AccountUpdatedEvent(_, account) =>
          for {
            _ <- analyticsStore.zoneStore.accountStore.update(previousZone, modified = timestamp, account)
            updatedTransactions = previousZone.transactions.values.filter(transaction =>
              transaction.from == account.id || transaction.to == account.id)
            _ <- analyticsStore.zoneStore.transactionStore.update(previousZone, updatedTransactions)
            _ <- analyticsStore.balanceStore.update(previousZone)(account -> previousBalances(account.id))
          } yield ()

        case TransactionAddedEvent(transaction) =>
          for {
            _ <- analyticsStore.zoneStore.transactionStore.add(previousZone)(transaction)
            updatedSourceBalance      = previousBalances(transaction.from) - transaction.value
            updatedDestinationBalance = previousBalances(transaction.to) + transaction.value
            _ <- analyticsStore.balanceStore.update(previousZone)(
              previousZone.accounts(transaction.from) -> updatedSourceBalance)
            _ <- analyticsStore.balanceStore.update(previousZone)(
              previousZone.accounts(transaction.to) -> updatedDestinationBalance)
          } yield ()
      }
      _ <- analyticsStore.zoneStore.updateModified(zoneId, timestamp)
      _ <- analyticsStore.journalSequenceNumberStore.update(zoneId, envelope.sequenceNr)
    } yield (envelope.sequenceNr, updatedZone, updatedBalances, updatedClients)
  }
}
