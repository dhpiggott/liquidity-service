package com.dhpcs.liquidity.models

import java.util.UUID

import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._

case class ZoneId(id: UUID) extends Identifier

object ZoneId extends IdentifierCompanion[ZoneId]

// TODO: Maximum member count, payment state, expiry date
case class Zone(name: String,
                equityAccountId: AccountId,
                members: Map[MemberId, Member],
                accounts: Map[AccountId, Account],
                transactions: Map[TransactionId, Transaction],
                created: Long,
                metadata: Option[JsObject] = None) {
  require(created >= 0)
}

object Zone {

  implicit val ZoneFormat: Format[Zone] = (
    (JsPath \ "name").format[String] and
      (JsPath \ "equityAccountId").format[AccountId] and
      (JsPath \ "members").format[Map[String, Member]] and
      (JsPath \ "accounts").format[Map[String, Account]] and
      (JsPath \ "transactions").format[Map[String, Transaction]] and
      (JsPath \ "created").format(min[Long](0)) and
      (JsPath \ "metadata").formatNullable[JsObject]
    )((name, equityAccountId, members, accounts, transactions, created, metadata) =>
    Zone(
      name,
      equityAccountId,
      members.map(e => (MemberId(UUID.fromString(e._1)), e._2)),
      accounts.map(e => (AccountId(UUID.fromString(e._1)), e._2)),
      transactions.map(e => (TransactionId(UUID.fromString(e._1)), e._2)),
      created,
      metadata
    ), zone =>
    (zone.name,
      zone.equityAccountId,
      zone.members.map { case (memberId, member) => (memberId.id.toString, member) },
      zone.accounts.map { case (accountId, account) => (accountId.id.toString, account) },
      zone.transactions.map { case (transactionId, transaction) => (transactionId.id.toString, transaction) },
      zone.created,
      zone.metadata)
    )

}