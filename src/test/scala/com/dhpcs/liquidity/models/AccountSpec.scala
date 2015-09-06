package com.dhpcs.liquidity.models

import com.dhpcs.json.FormatBehaviors
import org.scalatest.{FunSpec, Matchers}
import play.api.data.validation.ValidationError
import play.api.libs.json._

class AccountSpec extends FunSpec with FormatBehaviors[Account] with Matchers {

  describe("A JsValue of the wrong type") {
    it should behave like readError(
      Json.parse( """0"""),
      JsError(List(
        (__ \ "id", List(ValidationError("error.path.missing"))),
        (__ \ "ownerMemberIds", List(ValidationError("error.path.missing")))
      ))
    )
  }

  describe("An Account") {
    describe("without metadata") {
      implicit val account = Account(
        AccountId(0),
        Some("Dave's account"),
        Set(MemberId(0))
      )
      implicit val accountJson = Json.parse( """{"id":0,"name":"Dave's account","ownerMemberIds":[0]}""")
      it should behave like read
      it should behave like write
    }
    describe("with metadata") {
      implicit val account = Account(
        AccountId(0),
        Some("Dave's account"),
        Set(MemberId(0)),
        Some(
          Json.obj(
            "hidden" -> true
          )
        )
      )
      implicit val accountJson = Json.parse( """{"id":0,"name":"Dave's account","ownerMemberIds":[0],"metadata":{"hidden":true}}""")
      it should behave like read
      it should behave like write
    }
  }

}