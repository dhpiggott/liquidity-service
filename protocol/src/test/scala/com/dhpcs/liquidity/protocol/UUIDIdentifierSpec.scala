package com.dhpcs.liquidity.protocol

import java.util.UUID

import com.dhpcs.json.FormatBehaviors
import com.dhpcs.liquidity.protocol.UUIDIdentifierSpec.TestUUIDIdentifier
import org.scalatest.{FunSpec, Matchers}
import play.api.data.validation.ValidationError
import play.api.libs.json.{JsError, Json, __}

object UUIDIdentifierSpec {

  case class TestUUIDIdentifier(id: UUID) extends UUIDIdentifier

  object TestUUIDIdentifier extends UUIDIdentifierCompanion[TestUUIDIdentifier]

}

class UUIDIdentifierSpec extends FunSpec with FormatBehaviors[TestUUIDIdentifier] with Matchers {
  describe("A JsValue of the wrong type")(
    it should behave like readError(
      Json.parse(
        """
          |0""".stripMargin
      ),
      JsError(List((__, List(ValidationError(
        "error.expected.uuid"
      )))))
    )
  )

  describe("A TestUUIDIdentifier") {
    implicit val testUUIDIdentifier = TestUUIDIdentifier(
      UUID.fromString("c65910f3-40b0-476d-a404-c0bcbb57f45a")
    )
    implicit val testUUIDIdentifierJson = Json.parse(
      """
        |"c65910f3-40b0-476d-a404-c0bcbb57f45a"""".stripMargin
    )
    it should behave like read
    it should behave like write
  }
}
