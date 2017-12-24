package com.dhpcs.liquidity.actor.protocol.zonevalidator

import java.net.InetAddress

import akka.typed.ActorRef
import com.dhpcs.liquidity.actor.protocol.clientconnection.{
  SerializableClientConnectionMessage,
  ZoneResponseEnvelope
}
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.persistence.zone.ZoneState
import com.dhpcs.liquidity.ws.protocol.ZoneCommand

sealed abstract class ZoneValidatorMessage

case object PublishZoneStatusTick extends ZoneValidatorMessage
final case class RemoveClient(
    clientConnection: ActorRef[SerializableClientConnectionMessage])
    extends ZoneValidatorMessage

sealed abstract class SerializableZoneValidatorMessage
    extends ZoneValidatorMessage
    with Serializable
case object StopZone extends SerializableZoneValidatorMessage
final case class GetZoneStateCommand(replyTo: ActorRef[ZoneState],
                                     zoneId: ZoneId)
    extends SerializableZoneValidatorMessage
final case class ZoneCommandEnvelope(replyTo: ActorRef[ZoneResponseEnvelope],
                                     zoneId: ZoneId,
                                     remoteAddress: InetAddress,
                                     publicKey: PublicKey,
                                     correlationId: Long,
                                     zoneCommand: ZoneCommand)
    extends SerializableZoneValidatorMessage
