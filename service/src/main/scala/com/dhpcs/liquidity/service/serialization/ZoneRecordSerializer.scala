package com.dhpcs.liquidity.service.serialization

import akka.actor.ExtendedActorSystem
import com.dhpcs.liquidity.actor.protocol.ProtoBindings._
import com.dhpcs.liquidity.persistence.zone._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.service.ProtoBindingBackedSerializer
import com.dhpcs.liquidity.service.ProtoBindingBackedSerializer.AnyRefProtoBinding

import scala.collection.immutable.Seq

class ZoneRecordSerializer(system: ExtendedActorSystem)
    extends ProtoBindingBackedSerializer(
      system,
      protoBindings = Seq(
        AnyRefProtoBinding[ZoneState, proto.persistence.zone.ZoneState],
        AnyRefProtoBinding[
          ZoneEventEnvelope,
          proto.persistence.zone.ZoneEventEnvelope
        ]
      ),
      identifier = 694082575
    )
