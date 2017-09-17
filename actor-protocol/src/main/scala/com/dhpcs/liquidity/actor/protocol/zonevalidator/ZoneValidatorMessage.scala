package com.dhpcs.liquidity.actor.protocol.zonevalidator

import java.net.InetAddress

import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.ws.protocol.{ZoneCommand, ZoneNotification, ZoneResponse}

sealed abstract class ZoneValidatorMessage              extends Serializable
final case class GetZoneStateCommand(zoneId: ZoneId)    extends ZoneValidatorMessage
final case class GetZoneStateResponse(state: ZoneState) extends ZoneValidatorMessage
final case class ZoneCommandEnvelope(zoneId: ZoneId,
                                     remoteAddress: InetAddress,
                                     publicKey: PublicKey,
                                     correlationId: Long,
                                     sequenceNumber: Long,
                                     deliveryId: Long,
                                     zoneCommand: ZoneCommand)
    extends ZoneValidatorMessage
final case class ZoneCommandReceivedConfirmation(zoneId: ZoneId, deliveryId: Long) extends ZoneValidatorMessage
final case class ZoneResponseEnvelope(correlationId: Long,
                                      sequenceNumber: Long,
                                      deliveryId: Long,
                                      zoneResponse: ZoneResponse)
    extends ZoneValidatorMessage
final case class ZoneNotificationEnvelope(zoneId: ZoneId,
                                          sequenceNumber: Long,
                                          deliveryId: Long,
                                          zoneNotification: ZoneNotification)
    extends ZoneValidatorMessage
