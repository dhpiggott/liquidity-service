package com.dhpcs.liquidity.actor.protocol.clientmonitor

import akka.typed.ActorRef
import com.dhpcs.liquidity.actor.protocol.clientconnection.ClientConnectionMessage
import com.dhpcs.liquidity.model.PublicKey

sealed abstract class ClientMonitorMessage extends Serializable

final case class UpsertActiveClientSummary(clientConnectionActorRef: ActorRef[ClientConnectionMessage],
                                           activeClientSummary: ActiveClientSummary)
    extends ClientMonitorMessage
case object LogActiveClientsCount                                                      extends ClientMonitorMessage
final case class GetActiveClientSummaries(replyTo: ActorRef[Set[ActiveClientSummary]]) extends ClientMonitorMessage

final case class ActiveClientSummary(publicKey: PublicKey)
