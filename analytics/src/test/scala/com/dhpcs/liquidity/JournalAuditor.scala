package com.dhpcs.liquidity

import java.text.{DateFormat, NumberFormat}
import java.util.{Currency, UUID}

import actors.ZoneValidator._
import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import com.dhpcs.liquidity.JournalAuditor._
import com.dhpcs.liquidity.models.{Zone, _}
import com.typesafe.config.ConfigFactory
import org.scalatest.{AsyncWordSpec, BeforeAndAfterAll, Matchers}

import scala.concurrent.Future

object JournalAuditor {
  private final val ZoneIdStringPattern =
    """ZoneId\(([0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\)""".r
}

class JournalAuditor extends AsyncWordSpec with Matchers with BeforeAndAfterAll {
  private[this] def config =
    ConfigFactory.parseString(
      """
        |persistence.journal.plugin = "cassandra-journal"
        |cassandra-journal.contact-points = ["localhost"]
      """.stripMargin)

  private[this] implicit val system = ActorSystem("liquidity", config)
  private[this] implicit val mat = ActorMaterializer()

  override def afterAll(): Unit = {
    mat.shutdown()
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  "The journal" must {
    "replay all zones' events" in {
      def idsAndEvents(): Future[Map[ZoneId, Seq[EventEnvelope]]] = {
        val readJournal = PersistenceQuery(system)
          .readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
        readJournal
          .currentPersistenceIds()
          .collect { case ZoneIdStringPattern(uuidString) =>
            ZoneId(UUID.fromString(uuidString))
          }
          .mapAsync(1)(zoneId =>
            readJournal
              .currentEventsByPersistenceId(zoneId.toString, 0, Long.MaxValue)
              .runFold[Seq[EventEnvelope]](Seq.empty)(_ :+ _)
              .map(zoneId -> _)
          )
          .runFold[Map[ZoneId, Seq[EventEnvelope]]](Map.empty)(_ + _)
      }
      def zoneAndBalances(events: Seq[Any]): (Zone, Map[AccountId, BigDecimal]) =
        events.tail.foldLeft[(Zone, Map[AccountId, BigDecimal])](
          events.head match {
            case zoneCreatedEvent: ZoneCreatedEvent =>
              (zoneCreatedEvent.zone, Map.empty[AccountId, BigDecimal].withDefaultValue(BigDecimal(0)))
          }) { case ((zone, balances), event) =>
          event match {
            case _: ZoneJoinedEvent => (zone, balances)
            case _: ZoneQuitEvent => (zone, balances)
            case ZoneNameChangedEvent(_, name) =>
              (zone.copy(name = name), balances)
            case MemberCreatedEvent(_, member) =>
              (zone.copy(members = zone.members + (member.id -> member)), balances)
            case MemberUpdatedEvent(_, member) =>
              (zone.copy(members = zone.members + (member.id -> member)), balances)
            case AccountCreatedEvent(_, account) =>
              (zone.copy(accounts = zone.accounts + (account.id -> account)), balances)
            case AccountUpdatedEvent(_, account) =>
              (zone.copy(accounts = zone.accounts + (account.id -> account)), balances)
            case TransactionAddedEvent(_, transaction) =>
              val updatedSourceBalance = balances(transaction.from) - transaction.value
              val updatedDestinationBalance = balances(transaction.to) + transaction.value
              (zone.copy(transactions = zone.transactions + (transaction.id -> transaction)), balances +
                (transaction.from -> updatedSourceBalance) +
                (transaction.to -> updatedDestinationBalance))
          }
        }
      def summarise(zoneAndBalances: (Zone, Map[AccountId, BigDecimal])): String = {
        val (zone, balances) = zoneAndBalances
        val unnamed = "<unnamed>"
        val dateFormat = DateFormat.getDateTimeInstance
        val longestMemberNameLength = (zone.members.values.map(_.name.getOrElse(unnamed).length).toSeq :+ 0).max
        val currencyFormat = NumberFormat.getCurrencyInstance
        zone.metadata.flatMap(metadata => (metadata \ "currency").asOpt[String])
          .map(Currency.getInstance)
          .foreach(currencyFormat.setCurrency)
        s"""
           |ID: ${zone.id.id}
           |Name: ${zone.name.getOrElse(unnamed)}
           |Created: ${dateFormat.format(zone.created)}
           |Members:\n${
          zone.members.map { case (memberId, member) =>
            def balance(memberId: MemberId): BigDecimal =
              zone.accounts.collectFirst {
                case (accountId, account) if account.ownerMemberIds.contains(memberId) => balances(accountId)
              }.getOrElse(BigDecimal(0))
            val name = member.name.getOrElse(unnamed)
            val padding = " " * (longestMemberNameLength - name.length)
            s"    $name: $padding${currencyFormat.format(balance(memberId))}"
          }.mkString("\n")
        }
           |Transactions:\n${
          val longestTransactionValueLength =
            (zone.transactions.values.map(_.value).map(currencyFormat.format).map(_.length).toSeq :+ 0).max
          zone.transactions.values.toSeq.sortBy(_.created).map { transaction =>
            def memberName(accountId: AccountId): String = {
              val account = zone.accounts(accountId)
              val name = zone.members.collectFirst {
                case (memberId, member) if account.ownerMemberIds.contains(memberId) => member.name

              }.flatten.getOrElse(unnamed)
              val padding = " " * (longestMemberNameLength - name.length)
              s"$name$padding"

            }
            val from = memberName(transaction.from)
            val to = memberName(transaction.to)
            val value = currencyFormat.format(transaction.value)
            val padding = " " * (longestTransactionValueLength - value.length)
            val created = transaction.created
            s"    $from sent $value$padding to $to on ${dateFormat.format(created)}"
          }.mkString("\n")
        }
          """.stripMargin
      }
      for {
        zonesAndBalances <- idsAndEvents().map(
          _.values
            .map(
              _.map(_.event)
            )
            .map(zoneAndBalances)
            .toSeq
            .sortBy { case (zone, balances) => zone.created }
        )
      } yield {
        println(
          zonesAndBalances.map(summarise).mkString("\n")
        )
        println(s"Restored ${zonesAndBalances.size} zones")
        succeed
      }
    }
  }
}
