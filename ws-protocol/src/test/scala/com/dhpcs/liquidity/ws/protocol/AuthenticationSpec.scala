package com.dhpcs.liquidity.ws.protocol

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}

import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.ws.protocol.AuthenticationSpec._
import org.scalatest.FreeSpec

import scala.util.Random

object AuthenticationSpec {

  val (rsaPrivateKey: RSAPrivateKey, rsaPublicKey: RSAPublicKey) = {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(2048)
    val keyPair = keyPairGenerator.generateKeyPair
    (keyPair.getPrivate, keyPair.getPublic)
  }
}

class AuthenticationSpec extends FreeSpec {

  "The authentication protocol" - {
    "will accept valid signatures" in {
      val keyOwnershipChallengeMessage = Authentication.createKeyOwnershipChallengeMessage()
      val keyOwnershipProofMessage =
        Authentication.createKeyOwnershipProof(rsaPublicKey, rsaPrivateKey, keyOwnershipChallengeMessage)
      assert(Authentication.isValidKeyOwnershipProof(keyOwnershipChallengeMessage, keyOwnershipProofMessage))
    }
    "will reject invalid signatures" in {
      val keyOwnershipChallengeMessage = Authentication.createKeyOwnershipChallengeMessage()
      val invalidSignature             = new Array[Byte](256)
      Random.nextBytes(invalidSignature)
      val completeKeyOwnershipProofMessage = proto.ws.protocol.ServerMessage.KeyOwnershipProof(
        com.google.protobuf.ByteString.copyFrom(rsaPublicKey.getEncoded),
        com.google.protobuf.ByteString.copyFrom(invalidSignature)
      )
      assert(!Authentication.isValidKeyOwnershipProof(keyOwnershipChallengeMessage, completeKeyOwnershipProofMessage))
    }
  }
}
