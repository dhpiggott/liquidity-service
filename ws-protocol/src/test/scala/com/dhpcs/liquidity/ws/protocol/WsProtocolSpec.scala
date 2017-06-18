package com.dhpcs.liquidity.ws.protocol

import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import org.scalatest.FreeSpec

import scala.util.Random

class WsProtocolSpec extends FreeSpec {

  "The key ownership proof protocol" - {
    "will accept valid signatures" in {
      val keyOwnershipChallengeMessage = createKeyOwnershipChallengeMessage()
      val keyOwnershipProofMessage =
        createKeyOwnershipProof(ModelSpec.rsaPublicKey, ModelSpec.rsaPrivateKey, keyOwnershipChallengeMessage)
      assert(isValidKeyOwnershipProof(keyOwnershipChallengeMessage, keyOwnershipProofMessage))
    }
    "will reject invalid signatures" in {
      val keyOwnershipChallengeMessage = createKeyOwnershipChallengeMessage()
      val invalidSignature             = new Array[Byte](256)
      Random.nextBytes(invalidSignature)
      val completeKeyOwnershipProofMessage = proto.ws.protocol.ServerMessage.KeyOwnershipProof(
        com.google.protobuf.ByteString.copyFrom(ModelSpec.rsaPublicKey.getEncoded),
        com.google.protobuf.ByteString.copyFrom(invalidSignature)
      )
      assert(!isValidKeyOwnershipProof(keyOwnershipChallengeMessage, completeKeyOwnershipProofMessage))
    }
  }
}
