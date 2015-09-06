package com.dhpcs.liquidity.models

import java.security.KeyPairGenerator

import com.dhpcs.json.FormatBehaviors
import com.google.common.io.BaseEncoding
import org.scalatest._
import play.api.data.validation.ValidationError
import play.api.libs.json._

class MemberSpec extends FunSpec with FormatBehaviors[Member] with Matchers {

  describe("A JsValue of the wrong type") {
    it should behave like readError(
      Json.parse( """0"""),
      JsError(List(
        (__ \ "id", List(ValidationError("error.path.missing"))),
        (__ \ "ownerPublicKey", List(ValidationError("error.path.missing")))
      ))
    )
  }

  describe("A Member") {
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    describe("without metadata") {
      implicit val member = Member(MemberId(0), Some("Dave"), PublicKey(publicKeyBytes))
      implicit val memberJson = Json.parse( s"""{"id":0,"name":"Dave","ownerPublicKey":"${BaseEncoding.base64.encode(publicKeyBytes)}"}""")
      it should behave like read
      it should behave like write
    }
    describe("with metadata") {
      implicit val member = Member(
        MemberId(0),
        Some("Dave"),
        PublicKey(publicKeyBytes),
        Some(
          Json.obj(
            "color" -> "0x0000FF"
          )
        )
      )
      implicit val memberJson = Json.parse( s"""{"id":0,"name":"Dave","ownerPublicKey":"${BaseEncoding.base64.encode(publicKeyBytes)}","metadata":{"color":"0x0000FF"}}""")
      it should behave like read
      it should behave like write
    }
  }

}