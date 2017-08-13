package com.dhpcs.liquidity.actor.protocol.clientmonitor

import akka.actor.ActorRef
import akka.typed
import com.dhpcs.liquidity.model.PublicKey

final case class ActiveClientSummary(publicKey: PublicKey)

sealed abstract class ClientMonitorMessage extends Serializable

final case class UpsertActiveClientSummary(clientConnectionActorRef: ActorRef, activeClientSummary: ActiveClientSummary)
    extends ClientMonitorMessage
case object LogActiveClientsCount extends ClientMonitorMessage
final case class GetActiveClientSummaries(replyTo: typed.ActorRef[Set[ActiveClientSummary]])
    extends ClientMonitorMessage
