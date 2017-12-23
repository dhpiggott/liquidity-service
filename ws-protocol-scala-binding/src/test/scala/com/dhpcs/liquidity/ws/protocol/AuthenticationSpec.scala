package com.dhpcs.liquidity.ws.protocol

import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.testkit.TestKit
import org.scalatest.FreeSpec

import scala.util.Random

class AuthenticationSpec extends FreeSpec {

  "The authentication protocol" - {
    "accepts valid signatures" in {
      val keyOwnershipChallengeMessage = Authentication.createKeyOwnershipChallengeMessage()
      val keyOwnershipProofMessage =
        Authentication.createKeyOwnershipProof(TestKit.rsaPublicKey,
                                               TestKit.rsaPrivateKey,
                                               keyOwnershipChallengeMessage)
      assert(Authentication.isValidKeyOwnershipProof(keyOwnershipChallengeMessage, keyOwnershipProofMessage))
    }
    "rejects invalid signatures" in {
      val keyOwnershipChallengeMessage = Authentication.createKeyOwnershipChallengeMessage()
      val invalidSignature             = new Array[Byte](256)
      Random.nextBytes(invalidSignature)
      val completeKeyOwnershipProofMessage = proto.ws.protocol.ServerMessage.KeyOwnershipProof(
        com.google.protobuf.ByteString.copyFrom(TestKit.rsaPublicKey.getEncoded),
        com.google.protobuf.ByteString.copyFrom(invalidSignature)
      )
      assert(!Authentication.isValidKeyOwnershipProof(keyOwnershipChallengeMessage, completeKeyOwnershipProofMessage))
    }
  }
}
