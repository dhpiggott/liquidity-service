package com.dhpcs.liquidity.models

import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._

case class TransactionId(id: Int) extends IntIdentifier

object TransactionId extends IntIdentifierCompanion[TransactionId]

case class Transaction(id: TransactionId,
                       from: AccountId,
                       to: AccountId,
                       value: BigDecimal,
                       creator: MemberId,
                       created: Long,
                       description: Option[String] = None,
                       metadata: Option[JsObject] = None) {
  require(value >= 0)
  require(created >= 0)
}

object Transaction {

  implicit val TransactionFormat: Format[Transaction] = (
    (JsPath \ "id").format[TransactionId] and
      (JsPath \ "from").format[AccountId] and
      (JsPath \ "to").format[AccountId] and
      (JsPath \ "value").format(min[BigDecimal](0)) and
      (JsPath \ "creator").format[MemberId] and
      (JsPath \ "created").format(min[Long](0)) and
      (JsPath \ "description").formatNullable[String] and
      (JsPath \ "metadata").formatNullable[JsObject]
    )((id, from, to, value, creator, created, description, metadata) =>
    Transaction(
      id,
      from,
      to,
      value,
      creator,
      created,
      description,
      metadata
    ), transaction =>
    (transaction.id,
      transaction.from,
      transaction.to,
      transaction.value,
      transaction.creator,
      transaction.created,
      transaction.description,
      transaction.metadata)
    )

}
