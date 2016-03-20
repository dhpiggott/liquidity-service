package com.dhpcs.liquidity.models

import java.util.UUID

import com.dhpcs.json.FormatBehaviors
import org.scalatest._
import play.api.data.validation.ValidationError
import play.api.libs.json._

case class TestUUIDIdentifier(id: UUID) extends UUIDIdentifier

object TestUUIDIdentifier extends UUIDIdentifierCompanion[TestUUIDIdentifier]

class UUIDIdentifierSpec extends FunSpec with FormatBehaviors[TestUUIDIdentifier] with Matchers {

  describe("A JsValue of the wrong type") {
    it should behave like readError(
      Json.parse( """0"""),
      JsError(List((__, List(ValidationError("error.expected.uuid")))))
    )
  }

  describe("A TestUUIDIdentifier") {
    implicit val testUUIDIdentifier = TestUUIDIdentifier(UUID.fromString("c65910f3-40b0-476d-a404-c0bcbb57f45a"))
    implicit val testUUIDIdentifierJson = Json.parse( """"c65910f3-40b0-476d-a404-c0bcbb57f45a"""")
    it should behave like read
    it should behave like write
  }

}
