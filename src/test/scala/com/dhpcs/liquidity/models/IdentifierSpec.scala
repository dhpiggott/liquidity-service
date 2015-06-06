package com.dhpcs.liquidity.models

import java.util.UUID

import org.scalatest._
import play.api.data.validation.ValidationError
import play.api.libs.json._

class IdentifierSpec extends FunSpec with Matchers {
  
  case class TestId(id: UUID) extends Identifier

  object TestId extends IdentifierCompanion[TestId]

  def decodeError(badTestIdJson: JsValue, jsError: JsError) =
    it(s"$badTestIdJson should fail to decode with error $jsError") {
      Json.fromJson[TestId](badTestIdJson) should be(jsError)
    }

  def decode(implicit testIdJson: JsValue, testId: TestId) =
    it(s"$testIdJson should decode to $testId") {
      testIdJson.as[TestId] should be(testId)
    }

  def encode(implicit testId: TestId, testIdJson: JsValue) =
    it(s"$testId should encode to $testIdJson") {
      Json.toJson(testId) should be(testIdJson)
    }

  describe("A JsValue of the wrong type") {
    it should behave like decodeError(
      Json.parse("0"),
      JsError(List((__, List(ValidationError("error.expected.uuid")))))
    )
  }

  describe("A TestId") {
    implicit val testId = TestId(UUID.fromString("77bade6c-eeb3-4f0a-ad0f-5761426c3a7e"))
    implicit val testIdJson = Json.parse("\"77bade6c-eeb3-4f0a-ad0f-5761426c3a7e\"")
    it should behave like decode
    it should behave like encode
  }

}