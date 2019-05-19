package com.dhpcs.liquidity.ws.protocol

import com.dhpcs.liquidity.model.ProtoBindings._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.proto.binding.ProtoBindings._
import shapeless.cachedImplicit

object ProtoBindings {

  implicit final val UnitProtoBinding
    : ProtoBinding[Unit, com.google.protobuf.ByteString, Any] =
    ProtoBinding.instance(
      (_, _) => com.google.protobuf.ByteString.EMPTY,
      (_, _) => ()
    )

  implicit final val CreateZoneCommandProtoBinding
    : ProtoBinding[CreateZoneCommand,
                   proto.rest.protocol.CreateZoneCommand,
                   Any] =
    cachedImplicit

  implicit final val ZoneCommandProtoBinding
    : ProtoBinding[ZoneCommand, proto.rest.protocol.ZoneCommand, Any] =
    cachedImplicit

  implicit final val ZoneResponseProtoBinding
    : ProtoBinding[ZoneResponse, proto.rest.protocol.ZoneResponse, Any] =
    cachedImplicit

  implicit final val ZoneNotificationProtoBinding
    : ProtoBinding[ZoneNotification,
                   proto.rest.protocol.ZoneNotification,
                   Any] =
    cachedImplicit

}
