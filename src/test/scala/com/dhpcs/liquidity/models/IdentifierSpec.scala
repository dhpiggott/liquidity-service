package com.dhpcs.liquidity.models

import java.util.UUID

import com.dhpcs.json.FormatBehaviors
import org.scalatest._
import play.api.data.validation.ValidationError
import play.api.libs.json._

case class TestIdentifier(id: UUID) extends Identifier

object TestIdentifier extends IdentifierCompanion[TestIdentifier]

class IdentifierSpec extends FunSpec with FormatBehaviors[TestIdentifier] with Matchers {

  describe("A JsValue of the wrong type") {
    it should behave like readError(
      Json.parse( """0"""),
      JsError(List((__, List(ValidationError("error.expected.uuid")))))
    )
  }

  describe("A TestIdentifier") {
    implicit val testIdentifier = TestIdentifier(UUID.fromString("c65910f3-40b0-476d-a404-c0bcbb57f45a"))
    implicit val testIdentifierJson = Json.parse( """"c65910f3-40b0-476d-a404-c0bcbb57f45a"""")
    it should behave like read
    it should behave like write
  }

}