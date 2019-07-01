package com.dhpcs.liquidity.service.serialization

import akka.actor.ExtendedActorSystem
import com.dhpcs.liquidity.actor.protocol.ProtoBindings._
import com.dhpcs.liquidity.actor.protocol.liquidityserver._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.service.ProtoBindingBackedSerializer
import com.dhpcs.liquidity.service.ProtoBindingBackedSerializer.AnyRefProtoBinding

import scala.collection.immutable.Seq

class LiquidityServerMessageSerializer(system: ExtendedActorSystem)
    extends ProtoBindingBackedSerializer(
      system,
      protoBindings = Seq(
        AnyRefProtoBinding[
          ZoneResponseEnvelope,
          proto.actor.protocol.liquidityserver.ZoneResponseEnvelope
        ]
      ),
      identifier = 960694028
    )
