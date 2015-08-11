package com.dhpcs.liquidity.models

import java.util.UUID

import com.dhpcs.json.FormatBehaviors
import org.scalatest.{FunSpec, Matchers}
import play.api.data.validation.ValidationError
import play.api.libs.json._

class AccountSpec extends FunSpec with FormatBehaviors[Account] with Matchers {

  describe("A JsValue of the wrong type") {
    it should behave like readError(
      Json.parse( """0"""),
      JsError(List(
        (__ \ "owners", List(ValidationError("error.path.missing")))
      ))
    )
  }

  describe("An Account") {
    describe("without metadata") {
      implicit val account = Account(
        Some("Dave's account"),
        Set(MemberId(UUID.fromString("6709b5c8-1f18-491e-b703-d76baa261099")))
      )
      implicit val accountJson = Json.parse( """{"name":"Dave's account","owners":["6709b5c8-1f18-491e-b703-d76baa261099"]}""")
      it should behave like read
      it should behave like write
    }
    describe("with metadata") {
      implicit val account = Account(
        Some("Dave's account"),
        Set(MemberId(UUID.fromString("6709b5c8-1f18-491e-b703-d76baa261099"))),
        Some(
          Json.obj(
            "colour" -> "0x0000FF"
          )
        )
      )
      implicit val accountJson = Json.parse( """{"name":"Dave's account","owners":["6709b5c8-1f18-491e-b703-d76baa261099"],"metadata":{"colour":"0x0000FF"}}""")
      it should behave like read
      it should behave like write
    }
  }

}