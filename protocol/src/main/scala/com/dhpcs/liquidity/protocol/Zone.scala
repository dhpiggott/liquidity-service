package com.dhpcs.liquidity.protocol

import java.util.UUID

import play.api.libs.functional.syntax._
import play.api.libs.json.Reads.min
import play.api.libs.json.{Format, JsObject, JsPath}

case class ZoneId(id: UUID) extends UUIDIdentifier

object ZoneId extends UUIDIdentifierCompanion[ZoneId]

case class Zone(id: ZoneId,
                equityAccountId: AccountId,
                members: Map[MemberId, Member],
                accounts: Map[AccountId, Account],
                transactions: Map[TransactionId, Transaction],
                created: Long,
                expires: Long,
                name: Option[String] = None,
                metadata: Option[JsObject] = None) {
  require(created >= 0)
  require(expires >= 0)
}

object Zone {
  implicit final val ZoneFormat: Format[Zone] = (
    (JsPath \ "id").format[ZoneId] and
      (JsPath \ "equityAccountId").format[AccountId] and
      (JsPath \ "members").format[Seq[Member]] and
      (JsPath \ "accounts").format[Seq[Account]] and
      (JsPath \ "transactions").format[Seq[Transaction]] and
      (JsPath \ "created").format(min[Long](0)) and
      (JsPath \ "expires").format(min[Long](0)) and
      (JsPath \ "name").formatNullable[String] and
      (JsPath \ "metadata").formatNullable[JsObject]
    ) ((id, equityAccountId, members, accounts, transactions, created, expires, name, metadata) =>
    Zone(
      id,
      equityAccountId,
      members.map(e => e.id -> e).toMap,
      accounts.map(e => e.id -> e).toMap,
      transactions.map(e => e.id -> e).toMap,
      created,
      expires,
      name,
      metadata
    ), zone =>
    (zone.id,
      zone.equityAccountId,
      zone.members.values.toSeq,
      zone.accounts.values.toSeq,
      zone.transactions.values.toSeq,
      zone.created,
      zone.expires,
      zone.name,
      zone.metadata)
  )
}
