package com.dhpcs.liquidity.actor.protocol.clientconnection

import akka.typed.ActorRef
import com.dhpcs.liquidity.actor.protocol.zonevalidator.ZoneValidatorMessage
import com.dhpcs.liquidity.model.ZoneId
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.ws.protocol.{ZoneNotification, ZoneResponse}

case object ActorSinkAck

sealed abstract class ClientConnectionMessage
final case class InitActorSink(webSocketIn: ActorRef[ActorSinkAck.type]) extends ClientConnectionMessage
case object PublishClientStatusTick                                      extends ClientConnectionMessage
case object SendPingTick                                                 extends ClientConnectionMessage
// TODO: Not Any!
final case class ActorFlowServerMessage(webSocketIn: ActorRef[Any], serverMessage: proto.ws.protocol.ServerMessage)
    extends ClientConnectionMessage

sealed abstract class SerializableClientConnectionMessage extends ClientConnectionMessage with Serializable
final case class ZoneResponseEnvelope(zoneValidator: ActorRef[ZoneValidatorMessage],
                                      correlationId: Long,
                                      zoneResponse: ZoneResponse)
    extends SerializableClientConnectionMessage
final case class ZoneNotificationEnvelope(zoneValidator: ActorRef[ZoneValidatorMessage],
                                          zoneId: ZoneId,
                                          sequenceNumber: Long,
                                          zoneNotification: ZoneNotification)
    extends SerializableClientConnectionMessage
