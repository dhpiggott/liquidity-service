package com.dhpcs.liquidity.models

import java.util.UUID

import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._

case class ZoneId(id: UUID) extends UUIDIdentifier

object ZoneId extends UUIDIdentifierCompanion[ZoneId]

case class Zone(id: ZoneId,
                name: Option[String],
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
    (JsPath \ "id").format[ZoneId] and
      (JsPath \ "name").formatNullable[String] and
      (JsPath \ "equityAccountId").format[AccountId] and
      (JsPath \ "members").format[Seq[Member]] and
      (JsPath \ "accounts").format[Seq[Account]] and
      (JsPath \ "transactions").format[Seq[Transaction]] and
      (JsPath \ "created").format(min[Long](0)) and
      (JsPath \ "metadata").formatNullable[JsObject]
    )((id, name, equityAccountId, members, accounts, transactions, created, metadata) =>
    Zone(
      id,
      name,
      equityAccountId,
      members.map(e => e.id -> e).toMap,
      accounts.map(e => e.id -> e).toMap,
      transactions.map(e => e.id -> e).toMap,
      created,
      metadata
    ), zone =>
    (zone.id,
      zone.name,
      zone.equityAccountId,
      zone.members.values.toSeq,
      zone.accounts.values.toSeq,
      zone.transactions.values.toSeq,
      zone.created,
      zone.metadata)
    )

}