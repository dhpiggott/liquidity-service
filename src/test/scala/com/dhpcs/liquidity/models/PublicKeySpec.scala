package com.dhpcs.liquidity.models

import java.security.KeyPairGenerator

import org.scalatest._
import play.api.libs.json.Json

class PublicKeySpec extends FlatSpec with Matchers {

  "PublicKey formatting" should "be reversible" in {
    val publicKey = PublicKey(KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded)
    note(s"publicKey: $publicKey")
    val publicKeyJson = Json.toJson(publicKey)
    note(s"publicKeyJson: $publicKeyJson")
    publicKeyJson.as[PublicKey] should be(publicKey)
  }

}