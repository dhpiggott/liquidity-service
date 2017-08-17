package com.dhpcs.liquidity.server.actor

import java.time.Instant

import akka.actor.{Actor, ActorRef, ExtendedActorSystem, Props}
import akka.cluster.sharding.ShardRegion
import akka.pattern.pipe
import akka.persistence.query.EventEnvelope
import akka.persistence.query.scaladsl.{CurrentEventsByPersistenceIdQuery, EventsByPersistenceIdQuery, ReadJournal}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{KillSwitches, Materializer}
import cats.Cartesian
import cats.instances.option._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.persistence.zone._
import com.dhpcs.liquidity.server.CassandraAnalyticsStore
import com.dhpcs.liquidity.server.actor.ZoneAnalyticsActor.{Start, Started}

import scala.concurrent.Future

object ZoneAnalyticsActor {

  def props(readJournal: ReadJournal with CurrentEventsByPersistenceIdQuery with EventsByPersistenceIdQuery,
            futureAnalyticsStore: Future[CassandraAnalyticsStore],
            streamFailureHandler: PartialFunction[Throwable, Unit])(implicit mat: Materializer): Props =
    Props(new ZoneAnalyticsActor(readJournal, futureAnalyticsStore, streamFailureHandler, mat))

  final val ShardTypeName = "zone-analytics"

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case start @ Start(zoneId) =>
      (zoneId.id.toString, start)
  }

  private val NumberOfShards = 10

  val extractShardId: ShardRegion.ExtractShardId = {
    case Start(zoneId) =>
      (math.abs(zoneId.id.hashCode) % NumberOfShards).toString
  }

  final case class Start(zoneId: ZoneId)
  case object Started

}

class ZoneAnalyticsActor(
    readJournal: ReadJournal with CurrentEventsByPersistenceIdQuery with EventsByPersistenceIdQuery,
    futureAnalyticsStore: Future[CassandraAnalyticsStore],
    streamFailureHandler: PartialFunction[Throwable, Unit],
    _mat: Materializer)
    extends Actor {

  import context.dispatcher

  private[this] implicit val mat: Materializer = _mat

  private[this] val zoneId = ZoneId.fromPersistenceId(self.path.name)

  private[this] val killSwitch = KillSwitches.shared("zone-events")

  override def postStop(): Unit = {
    killSwitch.shutdown()
    super.postStop()
  }

  override def receive: Receive = {
    case _: Start =>
      val currentJournalState = for {
        analyticsStore         <- futureAnalyticsStore
        previousSequenceNumber <- analyticsStore.journalSequenceNumberStore.retrieve(zoneId)
        previousZone <- previousSequenceNumber match {
          case 0L => Future.successful(null)
          case _  => analyticsStore.zoneStore.retrieve(zoneId)
        }
        previousBalances <- analyticsStore.balanceStore.retrieve(zoneId)
        previousClients <- analyticsStore.clientStore.retrieve(zoneId)(context.dispatcher,
                                                                       context.system.asInstanceOf[ExtendedActorSystem])
        (currentSequenceNumber, currentZone, currentBalances, currentClients) <- readJournal
          .currentEventsByPersistenceId(zoneId.persistenceId, previousSequenceNumber, Long.MaxValue)
          .runFoldAsync((previousSequenceNumber, previousZone, previousBalances, previousClients))(
            updateStoreAndApplyEvent(analyticsStore))
      } yield (analyticsStore, currentSequenceNumber, currentZone, currentBalances, currentClients)

      currentJournalState.map(_ => Started).pipeTo(sender())

      val done = Source
        .fromFuture(currentJournalState)
        .via(killSwitch.flow)
        .flatMapConcat {
          case (analyticsStore, currentSequenceNumber, currentZone, currentBalances, currentClients) =>
            readJournal
              .eventsByPersistenceId(zoneId.persistenceId, currentSequenceNumber, Long.MaxValue)
              .foldAsync((currentSequenceNumber, currentZone, currentBalances, currentClients))(
                updateStoreAndApplyEvent(analyticsStore))
        }
        .toMat(Sink.ignore)(Keep.right)
        .run()

      done.failed.foreach(t => streamFailureHandler.applyOrElse(t, throw _: Throwable))
  }

  private[this] def updateStoreAndApplyEvent(analyticsStore: CassandraAnalyticsStore)(
      previousSequenceNumberZoneBalancesAndClients: (Long,
                                                     Zone,
                                                     Map[AccountId, BigDecimal],
                                                     Map[ActorRef, (Instant, PublicKey)]),
      envelope: EventEnvelope)
    : Future[(Long, Zone, Map[AccountId, BigDecimal], Map[ActorRef, (Instant, PublicKey)])] = {

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
      case ClientJoinedEvent(maybeClientConnectionActorRef) =>
        Cartesian[Option].product(maybeClientConnectionActorRef, maybePublicKey) match {
          case None =>
            previousClients
          case Some((clientConnectionActorRef, publicKey)) =>
            previousClients + (clientConnectionActorRef -> (timestamp -> publicKey))
        }
      case ClientQuitEvent(maybeClientConnectionActorRef) =>
        maybeClientConnectionActorRef match {
          case None =>
            previousClients
          case Some(clientConnectionActorRef) =>
            previousClients - clientConnectionActorRef
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
        case ClientJoinedEvent(maybeClientConnectionActorRef) =>
          Cartesian[Option].product(maybeClientConnectionActorRef, maybePublicKey) match {
            case None =>
              Future.successful(())
            case Some((clientConnectionActorRef, publicKey)) =>
              analyticsStore.clientStore.createOrUpdate(zoneId,
                                                        publicKey,
                                                        joined = timestamp,
                                                        actorRef = clientConnectionActorRef)
          }
        case ClientQuitEvent(maybeClientConnectionActorRef) =>
          maybeClientConnectionActorRef match {
            case None =>
              Future.successful(())
            case Some(clientConnectionActorRef) =>
              previousClients.get(clientConnectionActorRef) match {
                case None => Future.successful(())
                case Some((joined, publicKey)) =>
                  analyticsStore.clientStore.update(zoneId,
                                                    publicKey,
                                                    joined,
                                                    actorRef = clientConnectionActorRef,
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
