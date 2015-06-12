package com.dhpcs.liquidity.models

import java.util.UUID

import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._

case class ZoneId(id: UUID) extends Identifier

object ZoneId extends IdentifierCompanion[ZoneId]

case class Zone(name: String,
                zoneType: String,
                members: Map[MemberId, Member],
                accounts: Map[AccountId, Account],
                transactions: Map[TransactionId, Transaction],
                lastModified: Long)

object Zone {

  def apply(name: String, zoneType: String): Zone = Zone(
    name,
    zoneType,
    Map.empty,
    Map.empty,
    Map.empty,
    System.currentTimeMillis
  )

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

}