package com.dhpcs.liquidity.ws.protocol

import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import org.scalatest.FreeSpec

import scala.util.Random

class AuthenticationSpec extends FreeSpec {

  "The authentication protocol" - {
    "will accept valid signatures" in {
      val keyOwnershipChallengeMessage = Authentication.createKeyOwnershipChallengeMessage()
      val keyOwnershipProofMessage =
        Authentication.createKeyOwnershipProof(ModelSpec.rsaPublicKey,
                                               ModelSpec.rsaPrivateKey,
                                               keyOwnershipChallengeMessage)
      assert(Authentication.isValidKeyOwnershipProof(keyOwnershipChallengeMessage, keyOwnershipProofMessage))
    }
    "will reject invalid signatures" in {
      val keyOwnershipChallengeMessage = Authentication.createKeyOwnershipChallengeMessage()
      val invalidSignature             = new Array[Byte](256)
      Random.nextBytes(invalidSignature)
      val completeKeyOwnershipProofMessage = proto.ws.protocol.ServerMessage.KeyOwnershipProof(
        com.google.protobuf.ByteString.copyFrom(ModelSpec.rsaPublicKey.getEncoded),
        com.google.protobuf.ByteString.copyFrom(invalidSignature)
      )
      assert(!Authentication.isValidKeyOwnershipProof(keyOwnershipChallengeMessage, completeKeyOwnershipProofMessage))
    }
  }
}
