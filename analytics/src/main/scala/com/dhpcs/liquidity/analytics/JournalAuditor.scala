package com.dhpcs.liquidity.analytics

import java.text.{DateFormat, NumberFormat}
import java.util.{Currency, UUID}

import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.{ActorMaterializer, Materializer}
import com.dhpcs.liquidity.model._

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

object JournalAuditor {
  private final val SentinelZoneId = ZoneId(UUID.fromString("4cdcdb95-5647-4d46-a2f9-a68e9294d00a"))
  private final val ZoneIdStringPattern =
    """ZoneId\(([0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\)""".r

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem("liquidity")
    implicit val mat = ActorMaterializer()
    implicit val ec = ExecutionContext.global
    try
      Await.result(
        for {
          zonesAndBalances <- readZonesAndBalances()
          recoveredSentinelZone = zonesAndBalances.exists { case (zone, _, _) => zone.id == SentinelZoneId }
          sortedZonesAndBalances = zonesAndBalances.sortBy { case (_, modified, _) => modified }
          sortedSummaries = sortedZonesAndBalances.map(summarise)
        } yield {
          println(sortedSummaries.mkString("", "\n", "\n"))
          println(s"Recovered ${zonesAndBalances.size} zones")
          if (!recoveredSentinelZone) throw sys.error(s"Failed to recover sentinel zone $SentinelZoneId")
        },
        Duration.Inf)
    finally
      Await.result(
        system.terminate(),
        Duration.Inf
      )
  }

  private def readZonesAndBalances()(implicit system: ActorSystem,
                                     mat: Materializer): Future[Seq[(Zone, Long, Map[AccountId, BigDecimal])]] = {
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
          .runFold[(Zone, Long, Map[AccountId, BigDecimal])]((null, 0L, Map.empty.withDefaultValue(BigDecimal(0)))) {
          case ((currentZone, currentModified, currentBalances), envelope) =>
            val updatedZone = envelope.event match {
              case ZoneCreatedEvent(_, zone) =>
                zone
              case _: ZoneJoinedEvent =>
                currentZone
              case _: ZoneQuitEvent =>
                currentZone
              case ZoneNameChangedEvent(_, name) =>
                currentZone.copy(name = name)
              case MemberCreatedEvent(_, member) =>
                currentZone.copy(members = currentZone.members + (member.id -> member))
              case MemberUpdatedEvent(_, member) =>
                currentZone.copy(members = currentZone.members + (member.id -> member))
              case AccountCreatedEvent(_, account) =>
                currentZone.copy(accounts = currentZone.accounts + (account.id -> account))
              case AccountUpdatedEvent(_, account) =>
                currentZone.copy(accounts = currentZone.accounts + (account.id -> account))
              case TransactionAddedEvent(_, transaction) =>
                currentZone.copy(transactions = currentZone.transactions + (transaction.id -> transaction))
            }
            val updatedModified = Math.max(currentModified, envelope.event.asInstanceOf[Event].timestamp)
            val updatedBalances = envelope.event match {
              case TransactionAddedEvent(_, transaction) =>
                val updatedSourceBalance = currentBalances(transaction.from) - transaction.value
                val updatedDestinationBalance = currentBalances(transaction.to) + transaction.value
                currentBalances +
                  (transaction.from -> updatedSourceBalance) +
                  (transaction.to -> updatedDestinationBalance)
              case _ => currentBalances
            }
            (updatedZone, updatedModified, updatedBalances)
        }
      )
      .runFold(Seq.empty[(Zone, Long, Map[AccountId, BigDecimal])])(_ :+ _)
  }

  private def summarise(zoneAndBalances: (Zone, Long, Map[AccountId, BigDecimal])): String = {
    val (zone, modified, balances) = zoneAndBalances
    val unnamed = "<unnamed>"
    val dateFormat = DateFormat.getDateTimeInstance
    val longestMemberNameLength = (zone.members.values.map(_.name.getOrElse(unnamed).length).toSeq :+ 0).max
    val currency = zone.metadata.flatMap(metadata => (metadata \ "currency").asOpt[String])
      .map(Currency.getInstance)
      .getOrElse(Currency.getInstance("XXX"))
    val currencyFormat = NumberFormat.getCurrencyInstance
    currencyFormat.setCurrency(currency)
    s"""
       |ID: ${zone.id.id}
       |Name: ${zone.name.getOrElse(unnamed)}
       |Created: ${dateFormat.format(zone.created)}
       |Modified: ${dateFormat.format(modified)}
       |Members:\n${
      zone.members.values.toSeq.sortBy(_.name).par.map { member =>
        def balance(memberId: MemberId): BigDecimal =
          zone.accounts.collectFirst {
            case (accountId, account) if account.ownerMemberIds.contains(memberId) => balances(accountId)
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
