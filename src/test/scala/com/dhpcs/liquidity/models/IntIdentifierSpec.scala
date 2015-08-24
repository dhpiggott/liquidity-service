package com.dhpcs.liquidity.models

import com.dhpcs.json.FormatBehaviors
import org.scalatest._
import play.api.data.validation.ValidationError
import play.api.libs.json._

case class TestIntIdentifier(id: Int) extends IntIdentifier

object TestIntIdentifier extends IntIdentifierCompanion[TestIntIdentifier]

class IntIdentifierSpec extends FunSpec with FormatBehaviors[TestIntIdentifier] with Matchers {

  describe("A JsValue of the wrong type") {
    it should behave like readError(
      Json.parse( """"c65910f3-40b0-476d-a404-c0bcbb57f45a""""),
      JsError(List((__, List(ValidationError("error.expected.jsnumber")))))
    )
  }

  describe("A TestIntIdentifier") {
    implicit val testIntIdentifier = TestIntIdentifier(0)
    implicit val testIntIdentifierJson = Json.parse( """0""")
    it should behave like read
    it should behave like write
  }

}