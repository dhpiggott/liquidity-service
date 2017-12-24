package com.dhpcs.liquidity.server.serialization

import akka.actor.ExtendedActorSystem
import com.dhpcs.liquidity.actor.protocol.ProtoBindings._
import com.dhpcs.liquidity.actor.protocol.zonemonitor._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.server.ProtoBindingBackedSerializer
import com.dhpcs.liquidity.server.ProtoBindingBackedSerializer.AnyRefProtoBinding

import scala.collection.immutable.Seq

class ZoneMonitorMessageSerializer(system: ExtendedActorSystem)
    extends ProtoBindingBackedSerializer(
      system,
      protoBindings = Seq(
        AnyRefProtoBinding[
          UpsertActiveZoneSummary,
          proto.actor.protocol.zonemonitor.UpsertActiveZoneSummary]
      ),
      identifier = 1135983027
    )
