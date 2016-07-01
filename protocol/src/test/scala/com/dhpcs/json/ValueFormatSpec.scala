package com.dhpcs.json

import com.dhpcs.json.ValueFormatSpec.TestValue
import org.scalatest.{FunSpec, Matchers}
import play.api.data.validation.ValidationError
import play.api.libs.json.{JsError, Json, __}

object ValueFormatSpec {

  case class TestValue(value: String)

  object TestValue {
    implicit final val TestFormat = ValueFormat[TestValue, String](TestValue(_), _.value)
  }

}

class ValueFormatSpec extends FunSpec with FormatBehaviors[TestValue] with Matchers {
  describe("A JsValue of the wrong type")(
    it should behave like readError(
      Json.parse(
        """
          |0""".stripMargin
      ),
      JsError(List((__, List(ValidationError("error.expected.jsstring")))))
    )
  )

  describe("A TestValue") {
    implicit val testValue = TestValue("test")
    implicit val testValueJson = Json.parse(
      """
        |"test"""".stripMargin
    )
    it should behave like read
    it should behave like write
  }
}
