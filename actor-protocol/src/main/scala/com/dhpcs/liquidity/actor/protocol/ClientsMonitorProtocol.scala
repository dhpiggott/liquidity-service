package com.dhpcs.liquidity.actor.protocol

import akka.actor.ActorRef
import akka.typed
import com.dhpcs.liquidity.model.PublicKey

final case class ActiveClientSummary(publicKey: PublicKey)

sealed abstract class ClientsMonitorMessage extends Serializable

final case class UpsertActiveClientSummary(clientConnectionActorRef: ActorRef, activeClientSummary: ActiveClientSummary)
    extends ClientsMonitorMessage
case object LogActiveClientsCount extends ClientsMonitorMessage
final case class GetActiveClientSummaries(replyTo: typed.ActorRef[Set[ActiveClientSummary]])
    extends ClientsMonitorMessage
