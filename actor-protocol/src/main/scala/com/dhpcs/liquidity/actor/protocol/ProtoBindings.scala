package com.dhpcs.liquidity.actor.protocol

import akka.typed.ActorRef
import akka.typed.cluster.ActorRefResolver
import com.dhpcs.liquidity.proto.binding.ProtoBinding

object ProtoBindings {

  implicit def actorRefProtoBinding[A]: ProtoBinding[ActorRef[A], String, ActorRefResolver] =
    ProtoBinding.instance((actorRef, resolver) => resolver.toSerializationFormat(actorRef),
                          (actorRefString, resolver) => resolver.resolveActorRef(actorRefString))

}
