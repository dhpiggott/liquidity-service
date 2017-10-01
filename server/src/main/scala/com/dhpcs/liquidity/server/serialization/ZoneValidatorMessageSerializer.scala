package com.dhpcs.liquidity.server.serialization

import akka.actor.ExtendedActorSystem
import com.dhpcs.liquidity.actor.protocol.zonevalidator._
import com.dhpcs.liquidity.model.ProtoBindings._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.server.ProtoBindingBackedSerializer
import com.dhpcs.liquidity.server.ProtoBindingBackedSerializer.AnyRefProtoBinding
import com.dhpcs.liquidity.ws.protocol.ProtoBindings._

import scala.collection.immutable.Seq

class ZoneValidatorMessageSerializer(system: ExtendedActorSystem)
    extends ProtoBindingBackedSerializer(
      system,
      protoBindings = Seq(
        AnyRefProtoBinding[GetZoneStateCommand, proto.actor.protocol.zonevalidator.GetZoneStateCommand],
        AnyRefProtoBinding[ZoneCommandEnvelope, proto.actor.protocol.zonevalidator.ZoneCommandEnvelope]
      ),
      identifier = 1668336332
    )
