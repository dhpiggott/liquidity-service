package com.dhpcs.liquidity.models

import java.security.KeyPairGenerator

import com.dhpcs.json.FormatBehaviors
import com.google.common.io.BaseEncoding
import org.scalatest._
import play.api.data.validation.ValidationError
import play.api.libs.json._

class MemberSpec extends FunSpec with FormatBehaviors[Member] with Matchers {

  describe("A JsValue of the wrong type") {
    it should behave like decodeError(
      Json.parse("0"),
      JsError(List(
        (__ \ "name", List(ValidationError("error.path.missing"))),
        (__ \ "publicKey", List(ValidationError("error.path.missing")))
      ))
    )
  }

  describe("A Member") {
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    implicit val member = Member("Dave", PublicKey(publicKeyBytes))
    implicit val memberJson = Json.parse( s"""{"name":"Dave","publicKey":"${BaseEncoding.base64.encode(publicKeyBytes)}"}""")
    it should behave like decode
    it should behave like encode
  }

}