package com.dhpcs.liquidity.protocol

import com.dhpcs.json.FormatBehaviors
import com.dhpcs.liquidity.protocol.IntIdentifierSpec.TestIntIdentifier
import org.scalatest.{FunSpec, Matchers}
import play.api.data.validation.ValidationError
import play.api.libs.json.{JsError, Json, __}

object IntIdentifierSpec {

  case class TestIntIdentifier(id: Int) extends IntIdentifier

  object TestIntIdentifier extends IntIdentifierCompanion[TestIntIdentifier]

}

class IntIdentifierSpec extends FunSpec with FormatBehaviors[TestIntIdentifier] with Matchers {
  describe("A JsValue of the wrong type")(
    it should behave like readError(
      Json.parse(
        """
          |"c65910f3-40b0-476d-a404-c0bcbb57f45a"""".stripMargin
      ),
      JsError(List((__, List(ValidationError(
        "error.expected.jsnumber"
      )))))
    )
  )

  describe("A TestIntIdentifier") {
    implicit val testIntIdentifier = TestIntIdentifier(0)
    implicit val testIntIdentifierJson = Json.parse(
      """
        |0""".stripMargin
    )
    it should behave like read
    it should behave like write
  }
}
