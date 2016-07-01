package com.dhpcs.liquidity.protocol

import play.api.libs.json.{JsObject, Json}

case class AccountId(id: Int) extends IntIdentifier

object AccountId extends IntIdentifierCompanion[AccountId]

case class Account(id: AccountId,
                   ownerMemberIds: Set[MemberId],
                   name: Option[String] = None,
                   metadata: Option[JsObject] = None)

object Account {
  implicit final val AccountFormat = Json.format[Account]
}
