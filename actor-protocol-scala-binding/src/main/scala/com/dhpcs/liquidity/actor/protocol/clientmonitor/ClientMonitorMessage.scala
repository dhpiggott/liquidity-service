package com.dhpcs.liquidity.actor.protocol.clientmonitor

import java.net.InetAddress

import akka.actor.typed.ActorRef
import com.dhpcs.liquidity.model.PublicKey

final case class ActiveClientSummary(remoteAddress: InetAddress,
                                     publicKey: PublicKey,
                                     connectionId: String)

sealed abstract class ClientMonitorMessage
case object LogActiveClientsCount extends ClientMonitorMessage
final case class GetActiveClientSummaries(
    replyTo: ActorRef[Set[ActiveClientSummary]])
    extends ClientMonitorMessage

sealed abstract class SerializableClientMonitorMessage
    extends ClientMonitorMessage
    with Serializable
final case class UpsertActiveClientSummary(
    clientConnection: ActorRef[Nothing],
    activeClientSummary: ActiveClientSummary)
    extends SerializableClientMonitorMessage
