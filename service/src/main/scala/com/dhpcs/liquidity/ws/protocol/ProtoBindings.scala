package com.dhpcs.liquidity.ws.protocol

import cats.data.NonEmptyList
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

  implicit final val ZoneGrpcCommandProtoBinding
      : ProtoBinding[ZoneCommand, proto.grpc.protocol.ZoneCommand, Any] =
    cachedImplicit

  implicit final val ZoneGrpcResponseProtoBinding
      : ProtoBinding[ZoneResponse, proto.grpc.protocol.ZoneResponse, Any] =
    cachedImplicit

  implicit final val ZoneGrpcErrorNotificationProtoBinding
      : ProtoBinding[Errors, proto.grpc.protocol.Errors, Any] =
    ProtoBinding.instance(
      (s, _) =>
        proto.grpc.protocol.Errors(
          s.errors.toList
            .map(e => proto.grpc.protocol.Errors.Error(e.code, e.description))
        ),
      (p, _) =>
        Errors(
          NonEmptyList.fromListUnsafe(
            p.errors
              .map(e => ZoneNotification.Error(e.code, e.description))
              .toList
          )
        )
    )

  implicit final val ZoneGrpcNotificationProtoBinding: ProtoBinding[
    ZoneNotification,
    proto.grpc.protocol.ZoneNotification,
    Any
  ] =
    cachedImplicit

}
