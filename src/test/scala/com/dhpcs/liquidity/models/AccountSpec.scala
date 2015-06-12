package com.dhpcs.liquidity.models

import java.util.UUID

import org.scalatest._
import play.api.data.validation.ValidationError
import play.api.libs.json._

class AccountSpec extends FunSpec with Matchers {

  def decodeError(badAccountJson: JsValue, jsError: JsError) =
    it(s"$badAccountJson should fail to decode with error $jsError") {
      Json.fromJson[Account](badAccountJson) should be(jsError)
    }

  def decode(implicit accountJson: JsValue, account: Account) =
    it(s"$accountJson should decode to $account") {
      accountJson.as[Account] should be(account)
    }

  def encode(implicit account: Account, accountJson: JsValue) =
    it(s"$account should encode to $accountJson") {
      Json.toJson(account) should be(accountJson)
    }

  describe("A JsValue of the wrong type") {
    it should behave like decodeError(
      Json.parse("0"),
      JsError(List(
        (__ \ "owners", List(ValidationError("error.path.missing"))),
        (__ \ "name", List(ValidationError("error.path.missing")))
      ))
    )
  }

  describe("An Account") {
    implicit val account = Account(
      "Dave's account",
      Set(MemberId(UUID.fromString("6709b5c8-1f18-491e-b703-d76baa261099")))
    )
    implicit val accountJson = Json.parse( """{"name":"Dave's account","owners":["6709b5c8-1f18-491e-b703-d76baa261099"]}""")
    it should behave like decode
    it should behave like encode
  }

}