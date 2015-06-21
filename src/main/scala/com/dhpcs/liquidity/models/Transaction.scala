package com.dhpcs.liquidity.models

import java.util.UUID

import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._

case class TransactionId(id: UUID) extends Identifier

object TransactionId extends IdentifierCompanion[TransactionId]

case class Transaction(description: String,
                      // TODO: Add creator?
                       from: AccountId,
                       to: AccountId,
                      // TODO: Rename to value?
                       amount: BigDecimal,
                       created: Long) {
  require(amount > 0)
  require(created > 0)
}

object Transaction {

  implicit val TransactionFormat: Format[Transaction] = (
    (JsPath \ "description").format[String] and
      (JsPath \ "from").format[AccountId] and
      (JsPath \ "to").format[AccountId] and
      (JsPath \ "amount").format(min[BigDecimal](0)) and
      (JsPath \ "created").format(min[Long](0))
    )((description, from, to, amount, created) =>
    Transaction(
      description,
      from,
      to,
      amount,
      created
    ), transaction =>
    (transaction.description,
      transaction.from,
      transaction.to,
      transaction.amount,
      transaction.created)
    )

}