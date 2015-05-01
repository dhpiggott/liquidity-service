package com.dhpcs.liquidity.models

import play.api.libs.json._

case class Transaction(description: String,
                       from: AccountId,
                       to: AccountId,
                       amount: BigDecimal)

object Transaction {

  implicit val transactionFormat = Json.format[Transaction]

}