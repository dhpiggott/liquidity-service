package com.dhpcs.liquidity.models

import java.util.UUID

import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._

case class TransactionId(id: UUID) extends Identifier

object TransactionId extends IdentifierCompanion[TransactionId]

case class Transaction(description: String,
                       from: AccountId,
                       to: AccountId,
                       value: BigDecimal,
                       creator: MemberId,
                       created: Long,
                       metadata: Option[JsObject] = None) {
  require(value > 0)
  require(created > 0)
}

object Transaction {

  implicit val TransactionFormat: Format[Transaction] = (
    (JsPath \ "description").format[String] and
      (JsPath \ "from").format[AccountId] and
      (JsPath \ "to").format[AccountId] and
      (JsPath \ "value").format(min[BigDecimal](0)) and
      (JsPath \ "creator").format[MemberId] and
      (JsPath \ "created").format(min[Long](0)) and
      (JsPath \ "metadata").formatNullable[JsObject]
    )((description, from, to, value, creator, created, metadata) =>
    Transaction(
      description,
      from,
      to,
      value,
      creator,
      created,
      metadata
    ), transaction =>
    (transaction.description,
      transaction.from,
      transaction.to,
      transaction.value,
      transaction.creator,
      transaction.created,
      transaction.metadata)
    )

}