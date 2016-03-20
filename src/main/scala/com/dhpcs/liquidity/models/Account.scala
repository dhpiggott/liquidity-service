package com.dhpcs.liquidity.models

import play.api.libs.json._

case class AccountId(id: Int) extends IntIdentifier

object AccountId extends IntIdentifierCompanion[AccountId]

case class Account(id: AccountId,
                   ownerMemberIds: Set[MemberId],
                   name: Option[String] = None,
                   metadata: Option[JsObject] = None)

object Account {

  implicit val AccountFormat = Json.format[Account]

}
