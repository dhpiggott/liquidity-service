package com.dhpcs.liquidity.ws.protocol

import com.dhpcs.liquidity.model.ModelSpec._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.serialization.ProtoConverter
import com.dhpcs.liquidity.ws.protocol.WsProtocolSpec._
import org.scalatest.FreeSpec
import play.api.libs.json._

object WsProtocolSpec {

  val createZoneCommand = CreateZoneCommand(
    equityOwnerPublicKey = PublicKey(ModelSpec.publicKeyBytes),
    equityOwnerName = Some("Bank"),
    equityOwnerMetadata = None,
    equityAccountName = Some("Bank"),
    equityAccountMetadata = None,
    name = Some("Dave's zone"),
    metadata = Some(
      Json.obj(
        "currency" -> "GBP"
      )
    )
  )

  val createZoneCommandProto = proto.ws.protocol.ZoneCommand.CreateZoneCommand(
    equityOwnerPublicKey = com.google.protobuf.ByteString.copyFrom(publicKeyBytes),
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
