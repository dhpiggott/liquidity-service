package com.dhpcs.liquidity.models

import java.util.UUID

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class ZoneId(id: UUID) extends Identifier

object ZoneId extends IdentifierCompanion[ZoneId]

case class Zone(name: String,
                zoneType: String,
                members: Map[MemberId, Member],
                accounts: Map[AccountId, Account],
                transactions: Seq[Transaction],
                lastModified: Long)

object Zone {

  def apply(name: String, zoneType: String): Zone =
    Zone(name, zoneType, Map.empty, Map.empty, Seq.empty, System.currentTimeMillis)

  implicit val ZoneFormat: Format[Zone] = new Format[Zone] {

    override def reads(jsValue: JsValue) =
      ((JsPath \ "name").read[String] and
        (JsPath \ "type").read[String] and
        (JsPath \ "members").read[Map[String, Member]] and
        (JsPath \ "accounts").read[Map[String, Account]] and
        (JsPath \ "transactions").read[Seq[Transaction]] and
        (JsPath \ "lastModified").read[Long]
        )((name, zoneType, members, accounts, transactions, lastModified) => {
        Zone(
          name,
          zoneType,
          members.map(e => (MemberId(UUID.fromString(e._1)), e._2)),
          accounts.map(e => (AccountId(UUID.fromString(e._1)), e._2)),
          transactions,
          lastModified
        )
      }).reads(jsValue)

    override def writes(zone: Zone) = (
      (__ \ "name").write[String] and
        (__ \ "type").write[String] and
        (__ \ "members").write[Map[String, Member]] and
        (__ \ "accounts").write[Map[String, Account]] and
        (__ \ "transactions").write[Seq[Transaction]] and
        (__ \ "lastModified").write[Long]
      )((zone: Zone) => (
      zone.name,
      zone.zoneType,
      zone.members.map { case (memberId, member) => (memberId.id.toString, member) },
      zone.accounts.map { case (accountId, account) => (accountId.id.toString, account) },
      zone.transactions,
      zone.lastModified
      )).writes(zone)

  }

}