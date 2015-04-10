package models

import java.util.UUID

import play.api.libs.json._

case class AccountId(id: UUID)

object AccountId {

  def apply(): AccountId = AccountId(UUID.randomUUID)

  implicit val accountIdReads =
    __.read[UUID].map(AccountId(_))

  implicit val accountIdWrites = Writes[AccountId] {
    accountId => JsString(accountId.id.toString)
  }

}

case class Account(name: String,
                   owners: Set[MemberId])

object Account {

  implicit val accountFormat = Json.format[Account]

}