package com.dhpcs.liquidity.server.serialization

import akka.actor.ExtendedActorSystem
import com.dhpcs.liquidity.actor.protocol._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.serialization.ProtoConverterSerializer
import com.dhpcs.liquidity.serialization.ProtoConverterSerializer._

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
