package com.dhpcs.liquidity.models

import java.security.KeyPairGenerator

import com.dhpcs.json.FormatBehaviors
import okio.ByteString
import org.scalatest._
import play.api.data.validation.ValidationError
import play.api.libs.json._

class PublicKeySpec extends FunSpec with FormatBehaviors[PublicKey] with Matchers {

  describe("A JsValue of the wrong type") {
    it should behave like readError(
      Json.parse( """0"""),
      JsError(List((__, List(ValidationError("error.expected.jsstring")))))
    )
  }

  describe("A PublicKey") {
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    implicit val publicKey = PublicKey(publicKeyBytes)
    implicit val publicKeyJson = Json.parse( s""""${ByteString.of(publicKeyBytes: _*).base64}"""")
    it should behave like read
    it should behave like write
  }

}