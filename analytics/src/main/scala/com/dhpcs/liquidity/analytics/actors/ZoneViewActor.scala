package com.dhpcs.liquidity.analytics.actors

import java.util.UUID

import akka.actor.{Actor, Props}
import akka.cluster.sharding.ShardRegion
import akka.pattern.pipe
import akka.persistence.query.EventEnvelope
import akka.persistence.query.scaladsl.{CurrentEventsByPersistenceIdQuery, EventsByPersistenceIdQuery, ReadJournal}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{KillSwitches, Materializer}
import com.dhpcs.liquidity.analytics.CassandraViewClient
import com.dhpcs.liquidity.analytics.actors.ZoneViewActor.{Start, Started}
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.persistence._

import scala.concurrent.Future

object ZoneViewActor {

  def props(readJournal: ReadJournal with CurrentEventsByPersistenceIdQuery with EventsByPersistenceIdQuery,
            cassandraViewClient: CassandraViewClient,
            streamFailureHandler: PartialFunction[Throwable, Unit])(implicit mat: Materializer): Props =
    Props(new ZoneViewActor(readJournal, cassandraViewClient, streamFailureHandler))

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
  case object Started

}

class ZoneViewActor(readJournal: ReadJournal with CurrentEventsByPersistenceIdQuery with EventsByPersistenceIdQuery,
                    cassandraViewClient: CassandraViewClient,
                    streamFailureHandler: PartialFunction[Throwable, Unit])(implicit mat: Materializer)
    extends Actor {

  import context.dispatcher

  private[this] val zoneId = ZoneId(UUID.fromString(self.path.name))

  private[this] val killSwitch = KillSwitches.shared("zone-view-stream")

  override def postStop(): Unit = {
    killSwitch.shutdown()
    super.postStop()
  }

  override def receive: Receive = {
    case _: Start =>
      val currentJournalState = for {
        previousSequenceNumber <- cassandraViewClient.retrieveJournalSequenceNumber(zoneId)
        previousZone <- previousSequenceNumber match {
          case 0L => Future.successful(null)
          case _  => cassandraViewClient.retrieveZone(zoneId)
        }
        previousClientJoinCounts <- cassandraViewClient.retrieveClientJoinCounts(zoneId)
        previousBalances         <- cassandraViewClient.retrieveBalances(zoneId)
        (currentSequenceNumber, currentZone, currentClientJoinCounts, currentBalances) <- readJournal
          .currentEventsByPersistenceId(zoneId.persistenceId, previousSequenceNumber, Long.MaxValue)
          .runFoldAsync((previousSequenceNumber, previousZone, previousClientJoinCounts, previousBalances))(
            updateViewAndApplyEvent)
      } yield (currentSequenceNumber, currentZone, currentClientJoinCounts, currentBalances)

      currentJournalState.map(_ => Started).pipeTo(sender())

      val done = Source
        .fromFuture(currentJournalState)
        .via(killSwitch.flow)
        .flatMapConcat {
          case (currentSequenceNumber, currentZone, currentClientJoinCounts, currentBalances) =>
            readJournal
              .eventsByPersistenceId(zoneId.persistenceId, currentSequenceNumber, Long.MaxValue)
              .foldAsync((currentSequenceNumber, currentZone, currentClientJoinCounts, currentBalances))(
                updateViewAndApplyEvent)
        }
        .toMat(Sink.ignore)(Keep.right)
        .run()

      done.onFailure {
        case t =>
          streamFailureHandler.applyOrElse(t, throw _: Throwable)
      }
  }

  private[this] def updateViewAndApplyEvent(
      previousSequenceNumberZoneClientJoinCountsAndBalances: (Long,
                                                              Zone,
                                                              Map[PublicKey, Int],
                                                              Map[AccountId, BigDecimal]),
      envelope: EventEnvelope): Future[(Long, Zone, Map[PublicKey, Int], Map[AccountId, BigDecimal])] = {

    val (_, previousZone, previousClientJoinCounts, previousBalances) =
      previousSequenceNumberZoneClientJoinCountsAndBalances

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
        case ZoneJoinedEvent(timestamp, _, publicKey) =>
          for {
            _ <- cassandraViewClient.updateClient(publicKey)
            _ <- cassandraViewClient.updateZoneClient(
              zoneId,
              publicKey,
              zoneJoinCount = previousClientJoinCounts.getOrElse(publicKey, 0) + 1,
              lastJoined = timestamp)
          } yield ()
        case _: ZoneQuitEvent =>
          Future.successful(())
        case ZoneNameChangedEvent(timestamp, name) =>
          cassandraViewClient.changeZoneName(zoneId, modified = timestamp, name)
        case MemberCreatedEvent(timestamp, member) =>
          cassandraViewClient.createMember(zoneId, created = timestamp)(member)
        case MemberUpdatedEvent(timestamp, member) =>
          for {
            _ <- cassandraViewClient.updateMember(zoneId, modified = timestamp, member)
            updatedAccounts = previousZone.accounts.values.filter(_.ownerMemberIds.contains(member.id))
            _ <- cassandraViewClient.updateAccounts(previousZone, updatedAccounts)
            updatedTransactions = previousZone.transactions.values.filter(transaction =>
              updatedAccounts.exists(_.id == transaction.from) || updatedAccounts.exists(_.id == transaction.to))
            _ <- cassandraViewClient.updateTransactions(previousZone, updatedTransactions)
            _ <- cassandraViewClient.updateBalances(previousZone, updatedAccounts, previousBalances)
          } yield ()
        case AccountCreatedEvent(timestamp, account) =>
          for {
            _ <- cassandraViewClient.createAccount(previousZone, created = timestamp)(account)
            _ <- cassandraViewClient.createBalance(previousZone, BigDecimal(0))(account)
          } yield ()
        case AccountUpdatedEvent(timestamp, account) =>
          for {
            _ <- cassandraViewClient.updateAccount(previousZone, modified = timestamp, account)
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
        case _: ZoneNameChangedEvent =>
          Future.successful(())
        case event: Event =>
          cassandraViewClient.updateZoneModified(zoneId, event.timestamp)
      }
      _ <- cassandraViewClient.updateJournalSequenceNumber(zoneId, envelope.sequenceNr)
    } yield ()

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

    for (_ <- viewUpdate)
      yield (envelope.sequenceNr, updatedZone, updatedClientJoinCounts, updatedBalances)
  }
}
