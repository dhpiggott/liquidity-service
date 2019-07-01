package com.dhpcs.liquidity.service.serialization

import akka.actor.ExtendedActorSystem
import com.dhpcs.liquidity.actor.protocol.ProtoBindings._
import com.dhpcs.liquidity.actor.protocol.zonevalidator._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.service.ProtoBindingBackedSerializer
import com.dhpcs.liquidity.service.ProtoBindingBackedSerializer.AnyRefProtoBinding

import scala.collection.immutable.Seq

class ZoneValidatorMessageSerializer(system: ExtendedActorSystem)
    extends ProtoBindingBackedSerializer(
      system,
      protoBindings = Seq(
        AnyRefProtoBinding[
          GetZoneStateCommand,
          proto.actor.protocol.zonevalidator.GetZoneStateCommand
        ],
        AnyRefProtoBinding[
          ZoneCommandEnvelope,
          proto.actor.protocol.zonevalidator.ZoneCommandEnvelope
        ],
        AnyRefProtoBinding[
          ZoneNotificationSubscription,
          proto.actor.protocol.zonevalidator.ZoneNotificationSubscription
        ]
      ),
      identifier = 1668336332
    )
