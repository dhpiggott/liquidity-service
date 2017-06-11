package com.dhpcs.liquidity.ws.protocol

import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import org.scalatest.FreeSpec

import scala.util.Random

class WsProtocolSpec extends FreeSpec {

  "The key ownership proof protocol" - {
    "will accept valid signatures" in {
      val beginKeyOwnershipProofMessage =
        createBeginKeyOwnershipProofMessage(ModelSpec.rsaPublicKey)
      val keyOwnershipProofNonceMessage =
        createKeyOwnershipNonceMessage()
      val completeKeyOwnershipProofMessage =
        createCompleteKeyOwnershipProofMessage(ModelSpec.rsaPrivateKey, keyOwnershipProofNonceMessage)
      assert(
        isValidKeyOwnershipProof(beginKeyOwnershipProofMessage,
                                 keyOwnershipProofNonceMessage,
                                 completeKeyOwnershipProofMessage))
    }
    "will reject invalid signatures" in {
      val beginKeyOwnershipProofMessage =
        createBeginKeyOwnershipProofMessage(ModelSpec.rsaPublicKey)
      val keyOwnershipProofNonceMessage =
        createKeyOwnershipNonceMessage()
      val invalidSignature = new Array[Byte](256)
      Random.nextBytes(invalidSignature)
      val completeKeyOwnershipProofMessage = proto.ws.protocol.CompleteKeyOwnershipProof(
        com.google.protobuf.ByteString.copyFrom(invalidSignature)
      )
      assert(
        !isValidKeyOwnershipProof(beginKeyOwnershipProofMessage,
                                  keyOwnershipProofNonceMessage,
                                  completeKeyOwnershipProofMessage))
    }
  }
}
