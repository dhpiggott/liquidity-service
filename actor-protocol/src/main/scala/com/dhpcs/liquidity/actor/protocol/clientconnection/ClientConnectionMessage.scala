package com.dhpcs.liquidity.actor.protocol.clientconnection

import akka.actor.ActorRef
import akka.typed
import com.dhpcs.liquidity.actor.protocol.zonevalidator.ZoneValidatorMessage
import com.dhpcs.liquidity.model.ZoneId
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.ws.protocol.{ZoneNotification, ZoneResponse}

case object ActorSinkAck

sealed abstract class ClientConnectionMessage
final case class ActorSinkInit(webSocketIn: typed.ActorRef[ActorSinkAck.type]) extends ClientConnectionMessage
case object PublishClientStatusTick                                            extends ClientConnectionMessage
case object SendPingTick                                                       extends ClientConnectionMessage
final case class WrappedServerMessage(webSocketIn: ActorRef, serverMessage: proto.ws.protocol.ServerMessage)
    extends ClientConnectionMessage

sealed abstract class SerializableClientConnectionMessage extends ClientConnectionMessage with Serializable
final case class ZoneResponseEnvelope(zoneValidator: typed.ActorRef[ZoneValidatorMessage],
                                      correlationId: Long,
                                      zoneResponse: ZoneResponse)
    extends SerializableClientConnectionMessage
final case class ZoneNotificationEnvelope(zoneValidator: typed.ActorRef[ZoneValidatorMessage],
                                          zoneId: ZoneId,
                                          sequenceNumber: Long,
                                          zoneNotification: ZoneNotification)
    extends SerializableClientConnectionMessage
