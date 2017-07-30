package com.dhpcs.liquidity.actor

import com.dhpcs.liquidity.proto.binding.ProtoBinding

package object protocol {

  final val MaxStringLength = 160
  final val MaxMetadataSize = 1024

  final val ClientStatusTopic = "Client"
  final val ZoneStatusTopic   = "Zone"

  implicit final val JoinZoneCommandProtoBinding: ProtoBinding[JoinZoneCommand.type, com.google.protobuf.ByteString] =
    ProtoBinding.instance(
      _ => com.google.protobuf.ByteString.EMPTY,
      (_, _) => JoinZoneCommand
    )

  implicit final val QuitZoneCommandProtoBinding: ProtoBinding[QuitZoneCommand.type, com.google.protobuf.ByteString] =
    ProtoBinding.instance(
      _ => com.google.protobuf.ByteString.EMPTY,
      (_, _) => QuitZoneCommand
    )

  implicit final val UnitProtoBinding: ProtoBinding[Unit, com.google.protobuf.ByteString] =
    ProtoBinding.instance(
      _ => com.google.protobuf.ByteString.EMPTY,
      (_, _) => ()
    )

  implicit final val ZoneTerminatedNotificationProtoBinding
    : ProtoBinding[ZoneTerminatedNotification.type, com.google.protobuf.ByteString] =
    ProtoBinding.instance(
      _ => com.google.protobuf.ByteString.EMPTY,
      (_, _) => ZoneTerminatedNotification
    )

}
