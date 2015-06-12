package com.dhpcs.liquidity.models

import java.util.UUID

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
        (__ \ "from", List(ValidationError("error.path.missing"))),
        (__ \ "amount", List(ValidationError("error.path.missing"))),
        (__ \ "created", List(ValidationError("error.path.missing"))),
        (__ \ "description", List(ValidationError("error.path.missing"))),
        (__ \ "to", List(ValidationError("error.path.missing")))
      ))
    )
  }

  describe("A Transaction") {
    implicit val transaction = Transaction(
      "test",
      AccountId(UUID.fromString("28c331cd-35eb-45b2-a478-82334d7a4593")),
      AccountId(UUID.fromString("a1191a07-fc84-4245-975a-9798a9c26a9e")),
      BigDecimal(1000000),
      1434115187612L
    )
    implicit val transactionJson = Json.parse( """{"description":"test","from":"28c331cd-35eb-45b2-a478-82334d7a4593","to":"a1191a07-fc84-4245-975a-9798a9c26a9e","amount":1000000,"created":1434115187612}""")
    it should behave like decode
    it should behave like encode
  }

}