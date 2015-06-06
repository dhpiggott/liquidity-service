package com.dhpcs.liquidity.models

import java.util.UUID

import org.scalatest._
import play.api.data.validation.ValidationError
import play.api.libs.json._

class IdentifierSpec extends FunSpec with Matchers {

  case class TestIdentifier(id: UUID) extends Identifier

  object TestIdentifier extends IdentifierCompanion[TestIdentifier]

  def decodeError(badTestIdJson: JsValue, jsError: JsError) =
    it(s"$badTestIdJson should fail to decode with error $jsError") {
      Json.fromJson[TestIdentifier](badTestIdJson) should be(jsError)
    }

  def decode(implicit testIdentifierJson: JsValue, testIdentifier: TestIdentifier) =
    it(s"$testIdentifierJson should decode to $testIdentifier") {
      testIdentifierJson.as[TestIdentifier] should be(testIdentifier)
    }

  def encode(implicit testIdentifier: TestIdentifier, testIdentifierJson: JsValue) =
    it(s"$testIdentifier should encode to $testIdentifierJson") {
      Json.toJson(testIdentifier) should be(testIdentifierJson)
    }

  describe("A JsValue of the wrong type") {
    it should behave like decodeError(
      Json.parse("0"),
      JsError(List((__, List(ValidationError("error.expected.uuid")))))
    )
  }

  describe("A TestIdentifier") {
    implicit val testIdentifier = TestIdentifier(UUID.fromString("77bade6c-eeb3-4f0a-ad0f-5761426c3a7e"))
    implicit val testIdentifierJson = Json.parse("\"77bade6c-eeb3-4f0a-ad0f-5761426c3a7e\"")
    it should behave like decode
    it should behave like encode
  }

}