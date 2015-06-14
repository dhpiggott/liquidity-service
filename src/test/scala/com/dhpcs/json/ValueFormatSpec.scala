package com.dhpcs.json

import org.scalatest._
import play.api.data.validation.ValidationError
import play.api.libs.json._

case class TestValue(value: String)

object TestValue {

  implicit val TestFormat = ValueFormat[TestValue, String](apply, _.value)

}

class ValueFormatSpec extends FunSpec with FormatBehaviors[TestValue] with Matchers {

  describe("A JsValue of the wrong type") {
    it should behave like readError(
      Json.parse( """0"""),
      JsError(List((__, List(ValidationError("error.expected.jsstring")))))
    )
  }

  describe("A TestValue") {
    implicit val testValue = TestValue("test")
    implicit val testValueJson = Json.parse( """"test"""")
    it should behave like read
    it should behave like write
  }

}