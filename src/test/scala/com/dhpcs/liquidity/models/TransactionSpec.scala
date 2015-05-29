package com.dhpcs.liquidity.models

import org.scalatest._
import play.api.libs.json.Json

class TransactionSpec extends FlatSpec with Matchers {

  "Transaction formatting" should "be reversible" in {
    val transaction = Transaction("test", AccountId.generate, AccountId.generate, BigDecimal(1))
    note(s"transaction: $transaction}")
    val transactionJson = Json.toJson(transaction)
    note(s"transactionJson: $transactionJson")
    transactionJson.as[Transaction] should be(transaction)
  }

}