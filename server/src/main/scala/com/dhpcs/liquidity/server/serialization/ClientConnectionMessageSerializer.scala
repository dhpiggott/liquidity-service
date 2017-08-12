package com.dhpcs.liquidity.server.serialization

import akka.actor.ExtendedActorSystem
import com.dhpcs.liquidity.actor.protocol.clientconnection._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.server.ProtoBindingBackedSerializer
import com.dhpcs.liquidity.server.ProtoBindingBackedSerializer.AnyRefProtoBinding

import scala.collection.immutable.Seq

class ClientConnectionMessageSerializer(system: ExtendedActorSystem)
    extends ProtoBindingBackedSerializer(
      system,
      protoBindings = Seq(
        AnyRefProtoBinding[MessageReceivedConfirmation,
                           proto.actor.protocol.clientconnection.MessageReceivedConfirmation]
      ),
      identifier = 1909424086
    )
