package com.dhpcs.liquidity.server.serialization

import akka.actor.ExtendedActorSystem
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.persistence._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.serialization.ProtoConverterSerializer
import com.dhpcs.liquidity.serialization.ProtoConverterSerializer._

import scala.collection.immutable.Seq

class ZoneEventSerializer(system: ExtendedActorSystem)
    extends ProtoConverterSerializer(
      system,
      protoConverters = Seq(
        AnyRefProtoConverter[ZoneCreatedEvent, proto.persistence.ZoneCreatedEvent],
        AnyRefProtoConverter[ZoneJoinedEvent, proto.persistence.ZoneJoinedEvent],
        AnyRefProtoConverter[ZoneQuitEvent, proto.persistence.ZoneQuitEvent],
        AnyRefProtoConverter[ZoneNameChangedEvent, proto.persistence.ZoneNameChangedEvent],
        AnyRefProtoConverter[MemberCreatedEvent, proto.persistence.MemberCreatedEvent],
        AnyRefProtoConverter[MemberUpdatedEvent, proto.persistence.MemberUpdatedEvent],
        AnyRefProtoConverter[AccountCreatedEvent, proto.persistence.AccountCreatedEvent],
        AnyRefProtoConverter[AccountUpdatedEvent, proto.persistence.AccountUpdatedEvent],
        AnyRefProtoConverter[TransactionAddedEvent, proto.persistence.TransactionAddedEvent]
      ),
      identifier = 1474968907
    )
