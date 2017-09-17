package com.dhpcs.liquidity.server.serialization

import akka.actor.ExtendedActorSystem
import com.dhpcs.liquidity.ws.protocol.ProtoBindings._
import com.dhpcs.liquidity.actor.protocol.zonevalidator._
import com.dhpcs.liquidity.model.ProtoBindings._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.server.ProtoBindingBackedSerializer
import com.dhpcs.liquidity.server.ProtoBindingBackedSerializer.AnyRefProtoBinding

import scala.collection.immutable.Seq

class ZoneValidatorMessageSerializer(system: ExtendedActorSystem)
    extends ProtoBindingBackedSerializer(
      system,
      protoBindings = Seq(
        AnyRefProtoBinding[GetZoneStateCommand, proto.actor.protocol.zonevalidator.GetZoneStateCommand],
        AnyRefProtoBinding[GetZoneStateResponse, proto.actor.protocol.zonevalidator.GetZoneStateResponse],
        AnyRefProtoBinding[ZoneCommandEnvelope, proto.actor.protocol.zonevalidator.ZoneCommandEnvelope],
        AnyRefProtoBinding[ZoneCommandReceivedConfirmation,
                           proto.actor.protocol.zonevalidator.ZoneCommandReceivedConfirmation],
        AnyRefProtoBinding[ZoneResponseEnvelope, proto.actor.protocol.zonevalidator.ZoneResponseEnvelope],
        AnyRefProtoBinding[ZoneNotificationEnvelope, proto.actor.protocol.zonevalidator.ZoneNotificationEnvelope]
      ),
      identifier = 1668336332
    )
