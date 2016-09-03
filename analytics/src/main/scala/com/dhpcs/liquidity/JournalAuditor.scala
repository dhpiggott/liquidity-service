package com.dhpcs.liquidity

import java.text.{DateFormat, NumberFormat}
import java.util.{Currency, UUID}

import actors.ZoneValidator._
import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.{ActorMaterializer, Materializer}
import com.dhpcs.liquidity.models._
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

object JournalAuditor {
  private final val ZoneIdStringPattern =
    """ZoneId\(([0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\)""".r

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem(
      "liquidity",
      ConfigFactory.parseString(
        s"""
          |persistence.journal.plugin = "cassandra-journal"
          |cassandra-journal.contact-points = ["localhost"]
        """.stripMargin)
    )
    implicit val mat = ActorMaterializer()
    implicit val ec = scala.concurrent.ExecutionContext.global
    try
      Await.result(
        for {
          zonesAndBalances <- readZonesAndBalances()
          sortedZonesAndBalances = zonesAndBalances.sortBy { case (zone, _) => zone.created }
          sortedSummaries = sortedZonesAndBalances.map(summarise)
        } yield {
          println(sortedSummaries.mkString("", "\n", "\n"))
          println(s"Recovered ${zonesAndBalances.size} zones")
        },
        Duration.Inf)
    finally
      Await.result(
        system.terminate(),
        Duration.Inf
      )
  }

  private def readZonesAndBalances()(implicit system: ActorSystem,
                                     mat: Materializer): Future[Seq[(Zone, Map[AccountId, BigDecimal])]] = {
    val readJournal = PersistenceQuery(system)
      .readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
    readJournal
      .currentPersistenceIds()
      .collect { case ZoneIdStringPattern(uuidString) =>
        ZoneId(UUID.fromString(uuidString))
      }
      .mapAsyncUnordered(sys.runtime.availableProcessors)(zoneId =>
        readJournal
          .currentEventsByPersistenceId(zoneId.toString, 0, Long.MaxValue)
          .runFold[(Zone, Map[AccountId, BigDecimal])]((null, Map.empty)) { case ((zone, balances), envelope) =>
          envelope.event match {
            case zoneCreatedEvent: ZoneCreatedEvent =>
              (zoneCreatedEvent.zone, balances)
            case _: ZoneJoinedEvent =>
              (zone, balances)
            case _: ZoneQuitEvent =>
              (zone, balances)
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
              val updatedSourceBalance =
                balances.getOrElse(transaction.from, BigDecimal(0)) - transaction.value
              val updatedDestinationBalance =
                balances.getOrElse(transaction.to, BigDecimal(0)) + transaction.value
              (zone.copy(transactions = zone.transactions + (transaction.id -> transaction)), balances +
                (transaction.from -> updatedSourceBalance) +
                (transaction.to -> updatedDestinationBalance))
          }
        }
      )
      .runFold(Seq.empty[(Zone, Map[AccountId, BigDecimal])])(_ :+ _)
  }

  private def summarise(zoneAndBalances: (Zone, Map[AccountId, BigDecimal])): String = {
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
      zone.members.values.toSeq.sortBy(_.name).par.map { member =>
        def balance(memberId: MemberId): BigDecimal =
          zone.accounts.collectFirst {
            case (accountId, account) if account.ownerMemberIds.contains(memberId) =>
              balances.getOrElse(accountId, BigDecimal(0))
          }.getOrElse(BigDecimal(0))
        val name = member.name.getOrElse(unnamed)
        val padding = " " * (longestMemberNameLength - name.length)
        s"    $name: $padding${currencyFormat.format(balance(member.id))}"
      }.mkString("\n")
    }
       |Transactions:\n${
      val longestTransactionValueLength =
        (zone.transactions.values.map(_.value).map(currencyFormat.format).map(_.length).toSeq :+ 0).max
      zone.transactions.values.toSeq.sortBy(_.created).par.map { transaction =>
        def name(accountId: AccountId): String = {
          val account = zone.accounts(accountId)
          val name = zone.members.collectFirst {
            case (memberId, member) if account.ownerMemberIds.contains(memberId) => member.name

          }.flatten.getOrElse(unnamed)
          val padding = " " * (longestMemberNameLength - name.length)
          s"$name$padding"
        }
        val from = name(transaction.from)
        val to = name(transaction.to)
        val value = currencyFormat.format(transaction.value)
        val padding = " " * (longestTransactionValueLength - value.length)
        val created = transaction.created
        s"    $from sent $value$padding to $to on ${dateFormat.format(created)}"
      }.mkString("\n")
    }""".stripMargin
  }
}
