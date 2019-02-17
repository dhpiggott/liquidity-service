package com.dhpcs.liquidity.service.serialization

import akka.actor.ExtendedActorSystem
import com.dhpcs.liquidity.actor.protocol.ProtoBindings._
import com.dhpcs.liquidity.actor.protocol.clientconnection._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.service.ProtoBindingBackedSerializer
import com.dhpcs.liquidity.service.ProtoBindingBackedSerializer.AnyRefProtoBinding

import scala.collection.immutable.Seq

class ClientConnectionMessageSerializer(system: ExtendedActorSystem)
    extends ProtoBindingBackedSerializer(
      system,
      protoBindings = Seq(
        AnyRefProtoBinding[
          ZoneNotificationEnvelope,
          proto.actor.protocol.clientconnection.ZoneNotificationEnvelope]
      ),
      identifier = 1909424086
    )
