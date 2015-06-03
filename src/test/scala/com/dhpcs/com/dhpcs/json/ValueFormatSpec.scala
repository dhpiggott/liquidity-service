package com.dhpcs.com.dhpcs.json

import com.dhpcs.json.ValueFormat
import org.scalatest._
import play.api.libs.json.Json

// TODO: FunSpec
case class TestValue(value: String)

object TestValue {

  implicit val TestFormat = ValueFormat[TestValue, String](apply, _.value)

}

class ValueFormatSpec extends FlatSpec with Matchers {

  "Value formatting" should "be reversible" in {
    val testValue = TestValue("test")
    note(s"testValue: $testValue")
    val testValueJson = Json.toJson(testValue)
    note(s"testValueJson: $testValueJson")
    testValueJson.as[TestValue] should be(testValue)
  }

}