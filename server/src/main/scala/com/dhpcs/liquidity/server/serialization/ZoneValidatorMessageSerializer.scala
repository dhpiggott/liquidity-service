package com.dhpcs.liquidity.server.serialization

import akka.actor.ExtendedActorSystem
import com.dhpcs.liquidity.actor.protocol._
// TODO: Why doesn't IntelliJ recognise that this is needed?
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.serialization.ProtoConverterSerializer
import com.dhpcs.liquidity.serialization.ProtoConverterSerializer._

import scala.collection.immutable.Seq

class ZoneValidatorMessageSerializer(system: ExtendedActorSystem)
    extends ProtoConverterSerializer(
      system,
      protoConverters = Seq(
        AnyRefProtoConverter[AuthenticatedZoneCommandWithIds, proto.actor.protocol.AuthenticatedZoneCommandWithIds],
        AnyRefProtoConverter[ZoneCommandReceivedConfirmation, proto.actor.protocol.ZoneCommandReceivedConfirmation],
        AnyRefProtoConverter[ZoneAlreadyExists, proto.actor.protocol.ZoneAlreadyExists],
        AnyRefProtoConverter[ZoneRestarted, proto.actor.protocol.ZoneRestarted],
        AnyRefProtoConverter[ZoneResponseWithIds, proto.actor.protocol.ZoneResponseWithIds],
        AnyRefProtoConverter[ZoneNotificationWithIds, proto.actor.protocol.ZoneNotificationWithIds],
        AnyRefProtoConverter[ActiveZoneSummary, proto.actor.protocol.ActiveZoneSummary]
      ),
      identifier = 1668336332
    )
