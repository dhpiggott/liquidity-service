package com.dhpcs.liquidity.actor.protocol.zonevalidator

import java.net.InetAddress

import akka.typed
import com.dhpcs.liquidity.actor.protocol.clientconnection.ZoneResponseEnvelope
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.ws.protocol.ZoneCommand

sealed abstract class ZoneValidatorMessage

case object PublishZoneStatusTick extends ZoneValidatorMessage

sealed abstract class SerializableZoneValidatorMessage extends ZoneValidatorMessage with Serializable
case object PassivateZone                              extends SerializableZoneValidatorMessage
final case class GetZoneStateCommand(replyTo: typed.ActorRef[ZoneState], zoneId: ZoneId)
    extends SerializableZoneValidatorMessage
final case class ZoneCommandEnvelope(replyTo: typed.ActorRef[ZoneResponseEnvelope],
                                     zoneId: ZoneId,
                                     remoteAddress: InetAddress,
                                     publicKey: PublicKey,
                                     correlationId: Long,
                                     zoneCommand: ZoneCommand)
    extends SerializableZoneValidatorMessage
