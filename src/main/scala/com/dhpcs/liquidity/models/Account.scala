package com.dhpcs.liquidity.models

import java.util.UUID

import play.api.libs.json._

case class AccountId(id: UUID) extends Identifier

object AccountId extends IdentifierCompanion[AccountId]

case class Account(name: String,
                   owners: Set[MemberId])

object Account {

  implicit val AccountFormat = Json.format[Account]

}