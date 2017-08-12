package com.dhpcs.liquidity.server.serialization

import akka.actor.ExtendedActorSystem
import com.dhpcs.liquidity.model.ProtoBindings._
import com.dhpcs.liquidity.persistence._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.server.ProtoBindingBackedSerializer
import com.dhpcs.liquidity.server.ProtoBindingBackedSerializer.AnyRefProtoBinding

import scala.collection.immutable.Seq

class ZoneValidatorRecordSerializer(system: ExtendedActorSystem)
    extends ProtoBindingBackedSerializer(
      system,
      protoBindings = Seq(
        AnyRefProtoBinding[ZoneSnapshot, proto.persistence.ZoneSnapshot],
        AnyRefProtoBinding[ZoneCreatedEvent, proto.persistence.ZoneCreatedEvent],
        AnyRefProtoBinding[ZoneJoinedEvent, proto.persistence.ZoneJoinedEvent],
        AnyRefProtoBinding[ZoneQuitEvent, proto.persistence.ZoneQuitEvent],
        AnyRefProtoBinding[ZoneNameChangedEvent, proto.persistence.ZoneNameChangedEvent],
        AnyRefProtoBinding[MemberCreatedEvent, proto.persistence.MemberCreatedEvent],
        AnyRefProtoBinding[MemberUpdatedEvent, proto.persistence.MemberUpdatedEvent],
        AnyRefProtoBinding[AccountCreatedEvent, proto.persistence.AccountCreatedEvent],
        AnyRefProtoBinding[AccountUpdatedEvent, proto.persistence.AccountUpdatedEvent],
        AnyRefProtoBinding[TransactionAddedEvent, proto.persistence.TransactionAddedEvent]
      ),
      identifier = 1474968907
    )
