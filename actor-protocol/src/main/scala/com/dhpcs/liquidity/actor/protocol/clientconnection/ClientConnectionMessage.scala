package com.dhpcs.liquidity.actor.protocol.clientconnection

import akka.actor.ActorRef
import akka.typed
import com.dhpcs.liquidity.model.ZoneId
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.ws.protocol.{ZoneNotification, ZoneResponse}

case object ActorSinkAck

sealed abstract class ClientConnectionMessage extends Serializable

case object PublishStatusTick                                                  extends ClientConnectionMessage
case object SendPingTick                                                       extends ClientConnectionMessage
final case class ActorSinkInit(webSocketIn: typed.ActorRef[ActorSinkAck.type]) extends ClientConnectionMessage
final case class WrappedServerMessage(webSocketIn: ActorRef, serverMessage: proto.ws.protocol.ServerMessage)
    extends ClientConnectionMessage
final case class ZoneResponseEnvelope(zoneValidator: ActorRef, correlationId: Long, zoneResponse: ZoneResponse)
    extends ClientConnectionMessage
final case class ZoneNotificationEnvelope(zoneValidator: ActorRef,
                                          zoneId: ZoneId,
                                          sequenceNumber: Long,
                                          zoneNotification: ZoneNotification)
    extends ClientConnectionMessage
