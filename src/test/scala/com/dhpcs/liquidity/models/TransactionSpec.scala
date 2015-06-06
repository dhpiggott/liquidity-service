package com.dhpcs.liquidity.models

import org.scalatest._
import play.api.data.validation.ValidationError
import play.api.libs.json._

class TransactionSpec extends FunSpec with Matchers {

  def decodeError(badTransactionJson: JsValue, jsError: JsError) =
    it(s"$badTransactionJson should fail to decode with error $jsError") {
      Json.fromJson[Transaction](badTransactionJson) should be(jsError)
    }

  def decode(implicit transactionJson: JsValue, transaction: Transaction) =
    it(s"$transactionJson should decode to $transaction") {
      transactionJson.as[Transaction] should be(transaction)
    }

  def encode(implicit transaction: Transaction, transactionJson: JsValue) =
    it(s"$transaction should encode to $transactionJson") {
      Json.toJson(transaction) should be(transactionJson)
    }

  describe("A JsValue of the wrong type") {
    it should behave like decodeError(
      Json.parse("0"),
      JsError(List(
        (__ \ "description", List(ValidationError("error.path.missing"))),
        (__ \ "amount", List(ValidationError("error.path.missing"))),
        (__ \ "to", List(ValidationError("error.path.missing"))),
        (__ \ "from", List(ValidationError("error.path.missing")))
      ))
    )
  }

  describe("A Transaction") {
    val from = AccountId.generate
    val to = AccountId.generate
    implicit val transaction = Transaction("test", from, to, BigDecimal(1))
    implicit val transactionJson = Json.parse( s"""{\"description\":\"test\",\"from\":\"${from.id}\",\"to\":\"${to.id}\",\"amount\":1}""")
    it should behave like decode
    it should behave like encode
  }

}