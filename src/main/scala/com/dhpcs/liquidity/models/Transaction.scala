package com.dhpcs.liquidity.models

import java.util.UUID

import play.api.libs.json._

case class TransactionId(id: UUID) extends Identifier

object TransactionId extends IdentifierCompanion[TransactionId]

case class Transaction(description: String,
                       from: AccountId,
                       to: AccountId,
                       amount: BigDecimal,
                       created: Long)

object Transaction {

  implicit val TransactionFormat = Json.format[Transaction]

}