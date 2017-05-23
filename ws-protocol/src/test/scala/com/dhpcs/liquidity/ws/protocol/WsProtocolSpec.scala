package com.dhpcs.liquidity.ws.protocol

import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.serialization.ProtoConverter
import com.dhpcs.liquidity.ws.protocol.WsProtocolSpec._
import org.scalatest.FreeSpec

import scala.util.Random

object WsProtocolSpec {

  val createZoneCommand = CreateZoneCommand(
    equityOwnerPublicKey = PublicKey(ModelSpec.rsaPublicKey.getEncoded),
    equityOwnerName = Some("Bank"),
    equityOwnerMetadata = None,
    equityAccountName = Some("Bank"),
    equityAccountMetadata = None,
    name = Some("Dave's zone"),
    metadata = Some(
      com.google.protobuf.struct.Struct(
        Map(
          "currency" -> com.google.protobuf.struct.Value(com.google.protobuf.struct.Value.Kind.StringValue("GBP"))
        )))
  )

  val createZoneCommandProto = proto.ws.protocol.ZoneCommand.CreateZoneCommand(
    equityOwnerPublicKey = com.google.protobuf.ByteString.copyFrom(ModelSpec.rsaPublicKey.getEncoded),
    equityOwnerName = Some("Bank"),
    equityOwnerMetadata = None,
    equityAccountName = Some("Bank"),
    equityAccountMetadata = None,
    name = Some("Dave's zone"),
    metadata = Some(
      com.google.protobuf.struct.Struct(
        Map(
          "currency" -> com.google.protobuf.struct.Value(com.google.protobuf.struct.Value.Kind.StringValue("GBP"))
        )))
  )

}

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

  "A CreateZoneCommand" - {
    s"will convert to $createZoneCommandProto" in assert(
      ProtoConverter[CreateZoneCommand, proto.ws.protocol.ZoneCommand.CreateZoneCommand]
        .asProto(createZoneCommand) === createZoneCommandProto
    )
    s"will convert from $createZoneCommand" in assert(
      ProtoConverter[CreateZoneCommand, proto.ws.protocol.ZoneCommand.CreateZoneCommand]
        .asScala(createZoneCommandProto) === createZoneCommand
    )
  }
}
