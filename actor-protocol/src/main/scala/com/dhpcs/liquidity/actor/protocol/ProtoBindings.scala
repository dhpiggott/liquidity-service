package com.dhpcs.liquidity.actor.protocol

import akka.actor.ExtendedActorSystem
import akka.serialization.Serialization
import akka.typed.ActorRef
import akka.typed.scaladsl.adapter._
import com.dhpcs.liquidity.proto.binding.ProtoBinding

object ProtoBindings {

  implicit def actorRefProtoBinding[A]: ProtoBinding[ActorRef[A], String, ExtendedActorSystem] =
    ProtoBinding.instance(actorRef => Serialization.serializedActorPath(actorRef.toUntyped),
                          (s, system) => system.provider.resolveActorRef(s))

}
