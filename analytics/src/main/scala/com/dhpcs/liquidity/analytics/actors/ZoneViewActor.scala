package com.dhpcs.liquidity.analytics.actors

import java.util.UUID

import akka.actor.Status.Success
import akka.actor.{Actor, Props, Status}
import akka.cluster.sharding.ShardRegion
import akka.persistence.query.EventEnvelope
import akka.persistence.query.scaladsl.{CurrentEventsByPersistenceIdQuery, EventsByPersistenceIdQuery, ReadJournal}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{KillSwitches, Materializer}
import com.dhpcs.liquidity.analytics.CassandraViewClient
import com.dhpcs.liquidity.analytics.actors.ZoneViewActor.Start
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.persistence._

import scala.concurrent.Future
import scala.util.control.NonFatal

object ZoneViewActor {

  def props(readJournal: ReadJournal with CurrentEventsByPersistenceIdQuery with EventsByPersistenceIdQuery,
            cassandraViewClient: CassandraViewClient)(implicit mat: Materializer): Props =
    Props(new ZoneViewActor(readJournal, cassandraViewClient))

  final val ShardName = "ZoneView"

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

}

class ZoneViewActor(readJournal: ReadJournal with CurrentEventsByPersistenceIdQuery with EventsByPersistenceIdQuery,
                    cassandraViewClient: CassandraViewClient)(implicit mat: Materializer)
    extends Actor {

  import context.dispatcher

  private[this] val zoneId = ZoneId(UUID.fromString(self.path.name))

  private[this] val killSwitch = {

    val currentJournalState = for {
      previousSequenceNumber <- cassandraViewClient.retrieveJournalSequenceNumber(zoneId)
      previousZone <- previousSequenceNumber match {
        case 0L => Future.successful(null)
        case _  => cassandraViewClient.retrieveZone(zoneId)
      }
      previousBalances <- cassandraViewClient.retrieveBalances(zoneId)
      (currentSequenceNumber, currentZone, currentBalances) <- readJournal
        .currentEventsByPersistenceId(zoneId.persistenceId, previousSequenceNumber, Long.MaxValue)
        .runFoldAsync((previousSequenceNumber, previousZone, previousBalances))(updateViewAndApplyEvent)
    } yield (currentSequenceNumber, currentZone, currentBalances)

    val (killSwitch, done) = Source
      .fromFuture(currentJournalState)
      .viaMat(KillSwitches.single)(Keep.right)
      .flatMapConcat {
        case (currentSequenceNumber, currentZone, currentBalances) =>
          readJournal
            .eventsByPersistenceId(zoneId.persistenceId, currentSequenceNumber, Long.MaxValue)
            .foldAsync((currentSequenceNumber, currentZone, currentBalances))(updateViewAndApplyEvent)
      }
      .toMat(Sink.ignore)(Keep.both)
      .run()

    done.onFailure {
      case t => self ! Status.Failure(t)
    }

    killSwitch
  }

  override def postStop(): Unit = {
    killSwitch.shutdown()
    super.postStop()
  }

  override def receive: Receive = {
    case _: Start    => sender() ! Success(())
    case NonFatal(t) => throw t
  }

  private[this] def updateViewAndApplyEvent(
      previousSequenceNumberZoneAndBalances: (Long, Zone, Map[AccountId, BigDecimal]),
      envelope: EventEnvelope): Future[(Long, Zone, Map[AccountId, BigDecimal])] = {

    val (_, previousZone, previousBalances) = previousSequenceNumberZoneAndBalances

    val viewUpdate = for {
      _ <- envelope.event match {
        case ZoneCreatedEvent(_, zone) =>
          for {
            _ <- cassandraViewClient.createZone(zone)
            _ <- cassandraViewClient.createMembers(zone)
            _ <- cassandraViewClient.createAccounts(zone)
            _ <- cassandraViewClient.addTransactions(zone)
            _ <- cassandraViewClient.createBalances(zone, BigDecimal(0), zone.accounts.values)
          } yield ()
        case _: ZoneJoinedEvent =>
          Future.successful(())
        case _: ZoneQuitEvent =>
          Future.successful(())
        case ZoneNameChangedEvent(_, name) =>
          cassandraViewClient.changeZoneName(zoneId, name)
        case MemberCreatedEvent(_, member) =>
          cassandraViewClient.createMember(zoneId)(member)
        case MemberUpdatedEvent(_, member) =>
          for {
            _ <- cassandraViewClient.updateMember(zoneId, member)
            updatedAccounts = previousZone.accounts.values.filter(_.ownerMemberIds.contains(member.id))
            _ <- cassandraViewClient.updateAccounts(previousZone, updatedAccounts)
            updatedTransactions = previousZone.transactions.values.filter(transaction =>
              updatedAccounts.exists(_.id == transaction.from) || updatedAccounts.exists(_.id == transaction.to))
            _ <- cassandraViewClient.updateTransactions(previousZone, updatedTransactions)
            _ <- cassandraViewClient.updateBalances(previousZone, updatedAccounts, previousBalances)
          } yield ()
        case AccountCreatedEvent(_, account) =>
          for {
            _ <- cassandraViewClient.createAccount(previousZone)(account)
            _ <- cassandraViewClient.createBalance(previousZone, BigDecimal(0))(account)
          } yield ()
        case AccountUpdatedEvent(_, account) =>
          for {
            _ <- cassandraViewClient.updateAccount(previousZone)(account)
            updatedTransactions = previousZone.transactions.values.filter(transaction =>
              transaction.from == account.id || transaction.to == account.id)
            _ <- cassandraViewClient.updateTransactions(previousZone, updatedTransactions)
            _ <- cassandraViewClient.updateBalance(previousZone)(account -> previousBalances(account.id))
          } yield ()
        case TransactionAddedEvent(_, transaction) =>
          for {
            _ <- cassandraViewClient.addTransaction(previousZone)(transaction)
            updatedSourceBalance      = previousBalances(transaction.from) - transaction.value
            updatedDestinationBalance = previousBalances(transaction.to) + transaction.value
            _ <- cassandraViewClient.updateBalance(previousZone)(
              previousZone.accounts(transaction.from) -> updatedSourceBalance)
            _ <- cassandraViewClient.updateBalance(previousZone)(
              previousZone.accounts(transaction.to) -> updatedDestinationBalance)
          } yield ()
      }
      _ <- envelope.event match {
        case _: ZoneCreatedEvent =>
          Future.successful(())
        case event: Event =>
          cassandraViewClient.updateZoneModified(zoneId, event.timestamp)
      }
      _ <- cassandraViewClient.updateJournalSequenceNumber(zoneId, envelope.sequenceNr)
    } yield ()

    val updatedZone = envelope.event match {
      case ZoneCreatedEvent(_, zone) =>
        zone
      case _: ZoneJoinedEvent =>
        previousZone
      case _: ZoneQuitEvent =>
        previousZone
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

    for (_ <- viewUpdate)
      yield (envelope.sequenceNr, updatedZone, updatedBalances)
  }
}
