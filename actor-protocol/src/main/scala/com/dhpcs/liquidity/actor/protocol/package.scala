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
      _ => JoinZoneCommand
    )

  implicit final val QuitZoneCommandProtoBinding: ProtoBinding[QuitZoneCommand.type, com.google.protobuf.ByteString] =
    ProtoBinding.instance(
      _ => com.google.protobuf.ByteString.EMPTY,
      _ => QuitZoneCommand
    )

  implicit final val QuitZoneResponseProtoBinding
    : ProtoBinding[QuitZoneResponse.type, com.google.protobuf.ByteString] = ProtoBinding.instance(
    _ => com.google.protobuf.ByteString.EMPTY,
    _ => QuitZoneResponse
  )

  implicit final val ChangeZoneNameResponseProtoBinding
    : ProtoBinding[ChangeZoneNameResponse.type, com.google.protobuf.ByteString] = ProtoBinding.instance(
    _ => com.google.protobuf.ByteString.EMPTY,
    _ => ChangeZoneNameResponse
  )

  implicit final val UpdateMemberResponseProtoBinding
    : ProtoBinding[UpdateMemberResponse.type, com.google.protobuf.ByteString] = ProtoBinding.instance(
    _ => com.google.protobuf.ByteString.EMPTY,
    _ => UpdateMemberResponse
  )

  implicit final val UpdateAccountResponseProtoBinding
    : ProtoBinding[UpdateAccountResponse.type, com.google.protobuf.ByteString] = ProtoBinding.instance(
    _ => com.google.protobuf.ByteString.EMPTY,
    _ => UpdateAccountResponse
  )

  implicit final val ZoneTerminatedNotificationProtoBinding
    : ProtoBinding[ZoneTerminatedNotification.type, com.google.protobuf.ByteString] =
    ProtoBinding.instance(
      _ => com.google.protobuf.ByteString.EMPTY,
      _ => ZoneTerminatedNotification
    )

}
