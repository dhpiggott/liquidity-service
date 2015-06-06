package com.dhpcs.com.dhpcs.json

import com.dhpcs.json.ValueFormat
import org.scalatest._
import play.api.data.validation.ValidationError
import play.api.libs.json._

class ValueFormatSpec extends FunSpec with Matchers {

  case class TestValue(value: String)

  object TestValue {

    implicit val TestFormat = ValueFormat[TestValue, String](apply, _.value)

  }

  def decode(implicit testValueJson: JsValue, testValue: TestValue) =
    it(s"$testValueJson should decode to $testValue") {
      testValueJson.as[TestValue] should be(testValue)
    }

  def decodeError(badTestValueJson: JsValue, jsError: JsError) =
    it(s"$badTestValueJson should not decode") {
      Json.fromJson[TestValue](badTestValueJson) should be(jsError)
    }

  def encode(implicit testValue: TestValue, testValueJson: JsValue) =
    it(s"$testValue should encode to $testValueJson") {
      Json.toJson(testValue) should be(testValueJson)
    }

  describe("A TestValue") {
    implicit val testValue = TestValue("test")
    implicit val testValueJson = Json.parse("\"test\"")
    it should behave like decode
    it should behave like encode
    it should behave like decodeError(
      Json.parse("0"),
      JsError(List((__, List(ValidationError("error.expected.jsstring")))))
    )
  }

}