package com.dhpcs.liquidity.models

import java.security.KeyPairGenerator

import com.google.common.io.BaseEncoding
import org.scalatest._
import play.api.data.validation.ValidationError
import play.api.libs.json._

class PublicKeySpec extends FunSpec with Matchers {

  def decodeError(badPublicKeyJson: JsValue, jsError: JsError) =
    it(s"$badPublicKeyJson should fail to decode with error $jsError") {
      Json.fromJson[PublicKey](badPublicKeyJson) should be(jsError)
    }

  def decode(implicit publicKeyJson: JsValue, publicKey: PublicKey) =
    it(s"$publicKeyJson should decode to $publicKey") {
      publicKeyJson.as[PublicKey] should be(publicKey)
    }

  def encode(implicit publicKey: PublicKey, publicKeyJson: JsValue) =
    it(s"$publicKey should encode to $publicKeyJson") {
      Json.toJson(publicKey) should be(publicKeyJson)
    }

  describe("A JsValue of the wrong type") {
    it should behave like decodeError(
      Json.parse("0"),
      JsError(List((__, List(ValidationError("error.expected.jsstring")))))
    )
  }

  describe("A PublicKey") {
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    implicit val publicKey = PublicKey(publicKeyBytes)
    implicit val publicKeyJson = Json.parse( s"""\"${BaseEncoding.base64.encode(publicKeyBytes)}\"""")
    it should behave like decode
    it should behave like encode
  }

}