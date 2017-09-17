package com.dhpcs.liquidity.ws.protocol

import com.dhpcs.liquidity.proto.binding.ProtoBinding

object ProtoBindings {

  implicit final val JoinZoneCommandProtoBinding
    : ProtoBinding[JoinZoneCommand.type, com.google.protobuf.ByteString, Any] =
    ProtoBinding.instance(
      _ => com.google.protobuf.ByteString.EMPTY,
      (_, _) => JoinZoneCommand
    )

  implicit final val QuitZoneCommandProtoBinding
    : ProtoBinding[QuitZoneCommand.type, com.google.protobuf.ByteString, Any] =
    ProtoBinding.instance(
      _ => com.google.protobuf.ByteString.EMPTY,
      (_, _) => QuitZoneCommand
    )

  implicit final val UnitProtoBinding: ProtoBinding[Unit, com.google.protobuf.ByteString, Any] =
    ProtoBinding.instance(
      _ => com.google.protobuf.ByteString.EMPTY,
      (_, _) => ()
    )

}
