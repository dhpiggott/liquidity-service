package com.dhpcs.liquidity.server.serialization

import akka.actor.ExtendedActorSystem
import com.dhpcs.liquidity.actor.protocol._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.serialization.ProtoBindingBackedSerializer
import com.dhpcs.liquidity.serialization.ProtoBindingBackedSerializer.AnyRefProtoBinding

import scala.collection.immutable.Seq

class ZoneValidatorMessageSerializer(system: ExtendedActorSystem)
    extends ProtoBindingBackedSerializer(
      system,
      protoBindings = Seq(
        AnyRefProtoBinding[GetZoneStateCommand, proto.actor.protocol.GetZoneStateCommand],
        AnyRefProtoBinding[GetZoneStateResponse, proto.actor.protocol.GetZoneStateResponse],
        AnyRefProtoBinding[ZoneCommandEnvelope, proto.actor.protocol.ZoneCommandEnvelope],
        AnyRefProtoBinding[ZoneCommandReceivedConfirmation, proto.actor.protocol.ZoneCommandReceivedConfirmation],
        AnyRefProtoBinding[ZoneRestarted, proto.actor.protocol.ZoneRestarted],
        AnyRefProtoBinding[ZoneResponseEnvelope, proto.actor.protocol.ZoneResponseEnvelope],
        AnyRefProtoBinding[ZoneNotificationEnvelope, proto.actor.protocol.ZoneNotificationEnvelope],
        AnyRefProtoBinding[ActiveZoneSummary, proto.actor.protocol.ActiveZoneSummary]
      ),
      identifier = 1668336332
    )
