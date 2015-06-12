package com.dhpcs.liquidity.models

import java.util.UUID

import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._

import scala.collection.JavaConversions._

case class ZoneId(id: UUID) extends Identifier

object ZoneId extends IdentifierCompanion[ZoneId]

case class Zone(name: String,
                zoneType: String,
                members: Map[MemberId, Member],
                accounts: Map[AccountId, Account],
                transactions: Map[TransactionId, Transaction],
                lastModified: Long)

object Zone {

  implicit val ZoneFormat: Format[Zone] = (
    (JsPath \ "name").format[String] and
      (JsPath \ "type").format[String] and
      (JsPath \ "members").format[Map[String, Member]] and
      (JsPath \ "accounts").format[Map[String, Account]] and
      (JsPath \ "transactions").format[Map[String, Transaction]] and
      (JsPath \ "lastModified").format[Long]
    )((name, zoneType, members, accounts, transactions, lastModified) =>
    Zone(
      name,
      zoneType,
      members.map(e => (MemberId(UUID.fromString(e._1)), e._2)),
      accounts.map(e => (AccountId(UUID.fromString(e._1)), e._2)),
      transactions.map(e => (TransactionId(UUID.fromString(e._1)), e._2)),
      lastModified
    ), zone =>
    (zone.name,
      zone.zoneType,
      zone.members.map { case (memberId, member) => (memberId.id.toString, member) },
      zone.accounts.map { case (accountId, account) => (accountId.id.toString, account) },
      zone.transactions.map { case (transactionId, transaction) => (transactionId.id.toString, transaction) },
      zone.lastModified)
    )

  def apply(name: String, zoneType: String): Zone = Zone(
    name,
    zoneType,
    Map.empty,
    Map.empty,
    Map.empty,
    System.currentTimeMillis
  )

  def checkTransactionAndUpdateAccountBalances(zone: Zone,
                                               transaction: Transaction,
                                               accountBalances: Map[AccountId, BigDecimal]): Either[String, Map[AccountId, BigDecimal]] = {
    if (!zone.accounts.contains(transaction.from)) {
      Left(s"Invalid transaction source account: ${transaction.from}")
    } else if (!zone.accounts.contains(transaction.to)) {
      Left(s"Invalid transaction destination account: ${transaction.to}")
    } else {
      // TODO: Validate zone payment state for seigniorage, allow negative account values for special account types
      val newSourceBalance = accountBalances(transaction.from) - transaction.amount
      if (newSourceBalance < 0) {
        Left(s"Illegal transaction amount: ${transaction.amount}")
      } else {
        val newDestinationBalance = accountBalances(transaction.to) + transaction.amount
        Right(accountBalances
          + (transaction.from -> newSourceBalance)
          + (transaction.to -> newDestinationBalance))
      }
    }
  }

  def otherMembersAsJavaMap(zone: Zone, userPublicKey: PublicKey) =
    mapAsJavaMap(
      otherMembers(
        zone,
        userPublicKey
      )
    )

  def otherMembers(zone: Zone, userPublicKey: PublicKey) =
    zone.members.filter { case (_, member: Member) => member.publicKey != userPublicKey }

  def otherConnectedMembersAsJavaMap(otherMembers: java.util.Map[MemberId, Member],
                            connectedClients: java.util.Set[PublicKey]) =
    mapAsJavaMap(
      otherConnectedMembers(
        mapAsScalaMap(otherMembers).toMap,
        asScalaSet(connectedClients).toSet
      )
    )

  def otherConnectedMembers(otherMembers: Map[MemberId, Member], connectedClients: Set[PublicKey]) =
    otherMembers.filter { case (_, member: Member) => connectedClients.contains(member.publicKey) }

  def userMembersAsJavaMap(zone: Zone, userPublicKey: PublicKey) =
    mapAsJavaMap(
      userMembers(
        zone,
        userPublicKey
      )
    )

  def userMembers(zone: Zone, userPublicKey: PublicKey) =
    zone.members.filter { case (_, member: Member) => member.publicKey == userPublicKey }

}