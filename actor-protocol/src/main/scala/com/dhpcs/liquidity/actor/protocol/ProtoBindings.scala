package com.dhpcs.liquidity.actor.protocol

import akka.serialization.Serialization
import akka.typed.ActorRef
import akka.typed.cluster.ActorRefResolver
import akka.typed.scaladsl.adapter._
import com.dhpcs.liquidity.proto.binding.ProtoBinding

object ProtoBindings {

  // TODO: Support context in both directions, use resolver.toSerializationFormat for serialization
  implicit def actorRefProtoBinding[A]: ProtoBinding[ActorRef[A], String, ActorRefResolver] =
    ProtoBinding.instance(actorRef => Serialization.serializedActorPath(actorRef.toUntyped),
                          (s, resolver) => resolver.resolveActorRef(s))

}
