package com.dhpcs.liquidity.ws.protocol

import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.testkit.TestKit
import org.scalatest.FreeSpec

import scala.util.Random

class AuthenticationSpec extends FreeSpec {

  "The authentication protocol" - {
    "accepts valid signatures" in {
      val keyOwnershipChallenge = Authentication.createKeyOwnershipChallenge()
      val keyOwnershipProof =
        Authentication.createKeyOwnershipProof(TestKit.rsaPublicKey,
                                               TestKit.rsaPrivateKey,
                                               keyOwnershipChallenge)
      assert(
        Authentication.isValidKeyOwnershipProof(keyOwnershipChallenge,
                                                keyOwnershipProof))
    }
    "rejects invalid signatures" in {
      val keyOwnershipChallenge = Authentication.createKeyOwnershipChallenge()
      val invalidSignature = new Array[Byte](256)
      Random.nextBytes(invalidSignature)
      val keyOwnershipProof = proto.ws.protocol.ServerMessage.KeyOwnershipProof(
        com.google.protobuf.ByteString
          .copyFrom(TestKit.rsaPublicKey.getEncoded),
        com.google.protobuf.ByteString.copyFrom(invalidSignature)
      )
      assert(
        !Authentication.isValidKeyOwnershipProof(keyOwnershipChallenge,
                                                 keyOwnershipProof))
    }
  }
}
