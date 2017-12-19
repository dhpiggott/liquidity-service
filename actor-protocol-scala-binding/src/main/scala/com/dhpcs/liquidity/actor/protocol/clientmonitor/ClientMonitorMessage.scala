package com.dhpcs.liquidity.actor.protocol.clientmonitor

import akka.typed.ActorRef
import com.dhpcs.liquidity.actor.protocol.clientconnection.ClientConnectionMessage
import com.dhpcs.liquidity.model.PublicKey

final case class ActiveClientSummary(publicKey: PublicKey)

sealed abstract class ClientMonitorMessage
case object LogActiveClientsCount                                                      extends ClientMonitorMessage
final case class GetActiveClientSummaries(replyTo: ActorRef[Set[ActiveClientSummary]]) extends ClientMonitorMessage

sealed abstract class SerializableClientMonitorMessage extends ClientMonitorMessage with Serializable
final case class UpsertActiveClientSummary(clientConnection: ActorRef[ClientConnectionMessage],
                                           activeClientSummary: ActiveClientSummary)
    extends SerializableClientMonitorMessage
