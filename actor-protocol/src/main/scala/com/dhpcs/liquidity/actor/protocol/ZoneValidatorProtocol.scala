package com.dhpcs.liquidity.actor.protocol

import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.ws.protocol._
import play.api.libs.json._

sealed abstract class ZoneValidatorMessage extends Serializable

final case class AuthenticatedCommandWithIds(publicKey: PublicKey,
                                             command: Command,
                                             correlationId: Long,
                                             sequenceNumber: Long,
                                             deliveryId: Long)
    extends ZoneValidatorMessage

final case class EnvelopedAuthenticatedCommandWithIds(zoneId: ZoneId,
                                                      authenticatedCommandWithIds: AuthenticatedCommandWithIds)
    extends ZoneValidatorMessage

final case class CommandReceivedConfirmation(zoneId: ZoneId, deliveryId: Long) extends ZoneValidatorMessage

final case class ZoneAlreadyExists(createZoneCommand: CreateZoneCommand,
                                   correlationId: Long,
                                   sequenceNumber: Long,
                                   deliveryId: Long)
    extends ZoneValidatorMessage

final case class ZoneRestarted(zoneId: ZoneId) extends ZoneValidatorMessage

final case class ResponseWithIds(response: Response, correlationId: Long, sequenceNumber: Long, deliveryId: Long)
    extends ZoneValidatorMessage

final case class NotificationWithIds(notification: Notification, sequenceNumber: Long, deliveryId: Long)
    extends ZoneValidatorMessage

final case class ActiveZoneSummary(zoneId: ZoneId,
                                   metadata: Option[JsObject],
                                   members: Set[Member],
                                   accounts: Set[Account],
                                   transactions: Set[Transaction],
                                   clientConnections: Set[PublicKey])
    extends ZoneValidatorMessage

object ZoneValidatorMessage {

  implicit final val AuthenticatedCommandWithIdsFormat: Format[AuthenticatedCommandWithIds] =
    Json.format[AuthenticatedCommandWithIds]

  implicit final val EnvelopedAuthenticatedCommandWithIdsFormat: Format[EnvelopedAuthenticatedCommandWithIds] =
    Json.format[EnvelopedAuthenticatedCommandWithIds]

  implicit final val CommandReceivedConfirmationFormat: Format[CommandReceivedConfirmation] =
    Json.format[CommandReceivedConfirmation]

  implicit final val ZoneAlreadyExistsFormat: Format[ZoneAlreadyExists] = {
    implicit val createZoneCommandFormat = Json.format[CreateZoneCommand]
    Json.format[ZoneAlreadyExists]
  }

  implicit final val ZoneRestartedFormat: Format[ZoneRestarted]             = Json.format[ZoneRestarted]
  implicit final val ResponseWithIdsFormat: Format[ResponseWithIds]         = Json.format[ResponseWithIds]
  implicit final val NotificationWithIdsFormat: Format[NotificationWithIds] = Json.format[NotificationWithIds]
  implicit final val ActiveZoneSummaryFormat: Format[ActiveZoneSummary]     = Json.format[ActiveZoneSummary]

}
