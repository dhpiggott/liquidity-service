package com.dhpcs.liquidity.actor.protocol.zonevalidator

import java.net.InetAddress

import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.ws.protocol.ZoneCommand

sealed abstract class ZoneValidatorMessage extends Serializable

final case class GetZoneStateCommand(zoneId: ZoneId) extends ZoneValidatorMessage
final case class ZoneCommandEnvelope(zoneId: ZoneId,
                                     remoteAddress: InetAddress,
                                     publicKey: PublicKey,
                                     correlationId: Long,
                                     zoneCommand: ZoneCommand)
    extends ZoneValidatorMessage
