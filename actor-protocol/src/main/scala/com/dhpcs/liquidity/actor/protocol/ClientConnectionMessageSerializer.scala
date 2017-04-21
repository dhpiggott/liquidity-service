package com.dhpcs.liquidity.actor.protocol

import akka.actor.ExtendedActorSystem
import com.dhpcs.liquidity.actor.protocol.ProtoConverterSerializer._
import com.dhpcs.liquidity.proto

import scala.collection.immutable.Seq

class ClientConnectionMessageSerializer(system: ExtendedActorSystem)
    extends ProtoConverterSerializer(
      system,
      protoConverters = Seq(
        AnyRefProtoConverter[MessageReceivedConfirmation, proto.actor.protocol.MessageReceivedConfirmation],
        AnyRefProtoConverter[ActiveClientSummary, proto.actor.protocol.ActiveClientSummary]
      ),
      identifier = 1909424086
    )
