package com.dhpcs.liquidity.actor.protocol

import java.net.InetAddress

import akka.actor.typed.{ActorRef, ActorRefResolver}
import com.dhpcs.liquidity.actor.protocol.clientconnection._
import com.dhpcs.liquidity.actor.protocol.liquidityserver.ZoneResponseEnvelope
import com.dhpcs.liquidity.actor.protocol.zonemonitor._
import com.dhpcs.liquidity.actor.protocol.zonevalidator._
import com.dhpcs.liquidity.model.ProtoBindings._
import com.dhpcs.liquidity.persistence.zone._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.proto.binding.ProtoBindings._
import com.dhpcs.liquidity.ws.protocol.ProtoBindings._
import shapeless.cachedImplicit

object ProtoBindings {

  implicit def actorRefProtoBinding[A]
    : ProtoBinding[ActorRef[A], String, ActorRefResolver] =
    ProtoBinding.instance(
      (actorRef, resolver) => resolver.toSerializationFormat(actorRef),
      (actorRefString, resolver) => resolver.resolveActorRef(actorRefString))

  implicit final val GetZoneStateCommandProtoBinding
    : ProtoBinding[GetZoneStateCommand,
                   proto.actor.protocol.zonevalidator.GetZoneStateCommand,
                   ActorRefResolver] =
    cachedImplicit

  implicit final val ZoneCommandEnvelopeProtoBinding
    : ProtoBinding[ZoneCommandEnvelope,
                   proto.actor.protocol.zonevalidator.ZoneCommandEnvelope,
                   ActorRefResolver] =
    cachedImplicit

  implicit final val ZoneResponseEnvelopeProtoBinding
    : ProtoBinding[ZoneResponseEnvelope,
                   proto.actor.protocol.liquidityserver.ZoneResponseEnvelope,
                   ActorRefResolver] =
    cachedImplicit

  implicit final val ZoneNotificationSubscriptionProtoBinding: ProtoBinding[
    ZoneNotificationSubscription,
    proto.actor.protocol.zonevalidator.ZoneNotificationSubscription,
    ActorRefResolver] =
    cachedImplicit

  implicit final val ZoneNotificationEnvelopeProtoBinding: ProtoBinding[
    ZoneNotificationEnvelope,
    proto.actor.protocol.clientconnection.ZoneNotificationEnvelope,
    ActorRefResolver] = cachedImplicit

  implicit final val UpsertActiveZoneSummaryProtoBinding
    : ProtoBinding[UpsertActiveZoneSummary,
                   proto.actor.protocol.zonemonitor.UpsertActiveZoneSummary,
                   ActorRefResolver] = cachedImplicit

  implicit final val InetAddressProtoBinding
    : ProtoBinding[InetAddress, com.google.protobuf.ByteString, Any] =
    ProtoBinding.instance(
      (inetAddress, _) =>
        com.google.protobuf.ByteString.copyFrom(inetAddress.getAddress),
      (inetAddressBytes, _) =>
        InetAddress.getByAddress(inetAddressBytes.toByteArray)
    )

  implicit final val ZoneEventEnvelopeProtoBinding
    : ProtoBinding[ZoneEventEnvelope,
                   proto.persistence.zone.ZoneEventEnvelope,
                   ActorRefResolver] = cachedImplicit

  implicit final val ConnectionIdExtractor
    : EntityIdExtractor[ConnectedClient, ActorRef[ZoneNotificationEnvelope]] =
    EntityIdExtractor
      .instance[ConnectedClient, ActorRef[ZoneNotificationEnvelope]](
        _.connectionId)

  implicit final val ZoneStateProtoBinding
    : ProtoBinding[ZoneState,
                   proto.persistence.zone.ZoneState,
                   ActorRefResolver] = cachedImplicit

}
