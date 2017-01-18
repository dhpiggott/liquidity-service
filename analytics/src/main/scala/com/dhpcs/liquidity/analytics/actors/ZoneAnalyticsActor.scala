package com.dhpcs.liquidity.analytics.actors

import java.util.UUID

import akka.actor.{Actor, Props}
import akka.cluster.sharding.ShardRegion
import akka.pattern.pipe
import akka.persistence.query.EventEnvelope
import akka.persistence.query.scaladsl.{CurrentEventsByPersistenceIdQuery, EventsByPersistenceIdQuery, ReadJournal}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{KillSwitches, Materializer}
import com.dhpcs.liquidity.analytics.CassandraAnalyticsStore
import com.dhpcs.liquidity.analytics.actors.ZoneAnalyticsActor.{Start, Started}
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.persistence._

import scala.concurrent.Future

object ZoneAnalyticsActor {

  def props(readJournal: ReadJournal with CurrentEventsByPersistenceIdQuery with EventsByPersistenceIdQuery,
            futureAnalyticsStore: Future[CassandraAnalyticsStore],
            streamFailureHandler: PartialFunction[Throwable, Unit])(implicit mat: Materializer): Props =
    Props(new ZoneAnalyticsActor(readJournal, futureAnalyticsStore, streamFailureHandler))

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

  case class Start(zoneId: ZoneId)
  case object Started

}

class ZoneAnalyticsActor(
    readJournal: ReadJournal with CurrentEventsByPersistenceIdQuery with EventsByPersistenceIdQuery,
    futureAnalyticsStore: Future[CassandraAnalyticsStore],
    streamFailureHandler: PartialFunction[Throwable, Unit])(implicit mat: Materializer)
    extends Actor {

  import context.dispatcher

  private[this] val zoneId = ZoneId(UUID.fromString(self.path.name))

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
        previousBalances         <- analyticsStore.balanceStore.retrieve(zoneId)
        previousClientJoinCounts <- analyticsStore.clientStore.retrieveJoinCounts(zoneId)
        (currentSequenceNumber, currentZone, currentBalances, currentClientJoinCounts) <- readJournal
          .currentEventsByPersistenceId(zoneId.persistenceId, previousSequenceNumber, Long.MaxValue)
          .runFoldAsync((previousSequenceNumber, previousZone, previousBalances, previousClientJoinCounts))(
            updateStoreAndApplyEvent(analyticsStore))
      } yield (analyticsStore, currentSequenceNumber, currentZone, currentBalances, currentClientJoinCounts)

      currentJournalState.map(_ => Started).pipeTo(sender())

      val done = Source
        .fromFuture(currentJournalState)
        .via(killSwitch.flow)
        .flatMapConcat {
          case (analyticsStore, currentSequenceNumber, currentZone, currentBalances, currentClientJoinCounts) =>
            readJournal
              .eventsByPersistenceId(zoneId.persistenceId, currentSequenceNumber, Long.MaxValue)
              .foldAsync((currentSequenceNumber, currentZone, currentBalances, currentClientJoinCounts))(
                updateStoreAndApplyEvent(analyticsStore))
        }
        .toMat(Sink.ignore)(Keep.right)
        .run()

      done.onFailure {
        case t =>
          streamFailureHandler.applyOrElse(t, throw _: Throwable)
      }
  }

  private[this] def updateStoreAndApplyEvent(analyticsStore: CassandraAnalyticsStore)(
      previousSequenceNumberZoneBalancesAndClientJoinCounts: (Long,
                                                              Zone,
                                                              Map[AccountId, BigDecimal],
                                                              Map[PublicKey, Int]),
      envelope: EventEnvelope): Future[(Long, Zone, Map[AccountId, BigDecimal], Map[PublicKey, Int])] = {

    val (_, previousZone, previousBalances, previousClientJoinCounts) =
      previousSequenceNumberZoneBalancesAndClientJoinCounts

    val updatedZone = envelope.event match {
      case ZoneCreatedEvent(_, zone) =>
        zone
      case ZoneNameChangedEvent(_, name) =>
        previousZone.copy(name = name)
      case MemberCreatedEvent(_, member) =>
        previousZone.copy(members = previousZone.members + (member.id -> member))
      case MemberUpdatedEvent(_, member) =>
        previousZone.copy(members = previousZone.members + (member.id -> member))
      case AccountCreatedEvent(_, account) =>
        previousZone.copy(accounts = previousZone.accounts + (account.id -> account))
      case AccountUpdatedEvent(_, account) =>
        previousZone.copy(accounts = previousZone.accounts + (account.id -> account))
      case TransactionAddedEvent(_, transaction) =>
        previousZone.copy(transactions = previousZone.transactions + (transaction.id -> transaction))
      case _ =>
        previousZone
    }

    val updatedClientJoinCounts = envelope.event match {
      case ZoneJoinedEvent(_, _, publicKey) =>
        previousClientJoinCounts + (publicKey -> (previousClientJoinCounts.getOrElse(publicKey, 0) + 1))
      case _ =>
        previousClientJoinCounts
    }

    val updatedBalances = envelope.event match {
      case ZoneCreatedEvent(_, zone) =>
        previousBalances ++ zone.accounts.mapValues(_ => BigDecimal(0))
      case AccountCreatedEvent(_, account) =>
        previousBalances + (account.id -> BigDecimal(0))
      case TransactionAddedEvent(_, transaction) =>
        val updatedSourceBalance      = previousBalances(transaction.from) - transaction.value
        val updatedDestinationBalance = previousBalances(transaction.to) + transaction.value
        previousBalances + (transaction.from -> updatedSourceBalance) + (transaction.to -> updatedDestinationBalance)
      case _ => previousBalances
    }

    for {
      _ <- envelope.event match {
        case ZoneCreatedEvent(_, zone) =>
          for {
            _ <- analyticsStore.zoneStore.create(zone)
            _ <- analyticsStore.balanceStore.create(zone, BigDecimal(0), zone.accounts.values)
          } yield ()
        case ZoneJoinedEvent(timestamp, _, publicKey) =>
          for {
            _ <- analyticsStore.clientStore.update(publicKey)
            _ <- analyticsStore.clientStore.updateZone(
              zoneId,
              publicKey,
              zoneJoinCount = previousClientJoinCounts.getOrElse(publicKey, 0) + 1,
              lastJoined = timestamp)
          } yield ()
        case _: ZoneQuitEvent =>
          Future.successful(())
        case ZoneNameChangedEvent(timestamp, name) =>
          analyticsStore.zoneStore.changeName(zoneId, modified = timestamp, name)
        case MemberCreatedEvent(timestamp, member) =>
          analyticsStore.zoneStore.memberStore.create(zoneId, created = timestamp)(member)
        case MemberUpdatedEvent(timestamp, member) =>
          for {
            _ <- analyticsStore.zoneStore.memberStore.update(zoneId, modified = timestamp, member)
            updatedAccounts = previousZone.accounts.values.filter(_.ownerMemberIds.contains(member.id))
            _ <- analyticsStore.zoneStore.accountStore.update(previousZone, updatedAccounts)
            updatedTransactions = previousZone.transactions.values.filter(transaction =>
              updatedAccounts.exists(_.id == transaction.from) || updatedAccounts.exists(_.id == transaction.to))
            _ <- analyticsStore.zoneStore.transactionStore.update(previousZone, updatedTransactions)
            _ <- analyticsStore.balanceStore.update(previousZone, updatedAccounts, previousBalances)
          } yield ()
        case AccountCreatedEvent(timestamp, account) =>
          for {
            _ <- analyticsStore.zoneStore.accountStore.create(previousZone, created = timestamp)(account)
            _ <- analyticsStore.balanceStore.create(previousZone, BigDecimal(0))(account)
          } yield ()
        case AccountUpdatedEvent(timestamp, account) =>
          for {
            _ <- analyticsStore.zoneStore.accountStore.update(previousZone, modified = timestamp, account)
            updatedTransactions = previousZone.transactions.values.filter(transaction =>
              transaction.from == account.id || transaction.to == account.id)
            _ <- analyticsStore.zoneStore.transactionStore.update(previousZone, updatedTransactions)
            _ <- analyticsStore.balanceStore.update(previousZone)(account -> previousBalances(account.id))
          } yield ()
        case TransactionAddedEvent(_, transaction) =>
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
      _ <- envelope.event match {
        case _: ZoneCreatedEvent =>
          Future.successful(())
        case _: ZoneNameChangedEvent =>
          Future.successful(())
        case event: Event =>
          analyticsStore.zoneStore.updateModified(zoneId, event.timestamp)
      }
      _ <- analyticsStore.journalSequenceNumberStore.update(zoneId, envelope.sequenceNr)
    } yield (envelope.sequenceNr, updatedZone, updatedBalances, updatedClientJoinCounts)
  }
}
