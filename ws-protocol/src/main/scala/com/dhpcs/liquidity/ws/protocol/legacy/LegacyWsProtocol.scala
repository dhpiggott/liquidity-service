package com.dhpcs.liquidity.ws.protocol.legacy

import com.dhpcs.jsonrpc.Message.{MessageFormats, objectFormat}
import com.dhpcs.jsonrpc._
import com.dhpcs.liquidity.model._
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads.min
import play.api.libs.json._

sealed abstract class Message

sealed abstract class Command     extends Message
sealed abstract class ZoneCommand extends Command

case object EmptyZoneCommand extends ZoneCommand
final case class CreateZoneCommand(equityOwnerPublicKey: PublicKey,
                                   equityOwnerName: Option[String],
                                   equityOwnerMetadata: Option[JsObject],
                                   equityAccountName: Option[String],
                                   equityAccountMetadata: Option[JsObject],
                                   name: Option[String] = None,
                                   metadata: Option[JsObject] = None)
    extends ZoneCommand
final case class JoinZoneCommand(zoneId: ZoneId)                             extends ZoneCommand
final case class QuitZoneCommand(zoneId: ZoneId)                             extends ZoneCommand
final case class ChangeZoneNameCommand(zoneId: ZoneId, name: Option[String]) extends ZoneCommand
final case class CreateMemberCommand(zoneId: ZoneId,
                                     ownerPublicKey: PublicKey,
                                     name: Option[String] = None,
                                     metadata: Option[JsObject] = None)
    extends ZoneCommand
final case class UpdateMemberCommand(zoneId: ZoneId, member: Member) extends ZoneCommand
final case class CreateAccountCommand(zoneId: ZoneId,
                                      ownerMemberIds: Set[MemberId],
                                      name: Option[String] = None,
                                      metadata: Option[JsObject] = None)
    extends ZoneCommand
final case class UpdateAccountCommand(zoneId: ZoneId, account: Account) extends ZoneCommand
final case class AddTransactionCommand(zoneId: ZoneId,
                                       actingAs: MemberId,
                                       from: AccountId,
                                       to: AccountId,
                                       value: BigDecimal,
                                       description: Option[String] = None,
                                       metadata: Option[JsObject] = None)
    extends ZoneCommand {
  require(value >= 0)
}

object Command extends CommandCompanion[Command] {

  private final val AddTransactionCommandFormat: Format[AddTransactionCommand] = (
    (JsPath \ "zoneId").format[ZoneId] and
      (JsPath \ "actingAs").format[MemberId] and
      (JsPath \ "from").format[AccountId] and
      (JsPath \ "to").format[AccountId] and
      (JsPath \ "value").format(min[BigDecimal](0)) and
      (JsPath \ "description").formatNullable[String] and
      (JsPath \ "metadata").formatNullable[JsObject]
  )(
    (zoneId, actingAs, from, to, value, description, metadata) =>
      AddTransactionCommand(
        zoneId,
        actingAs,
        from,
        to,
        value,
        description,
        metadata
    ),
    addTransactionCommand =>
      (addTransactionCommand.zoneId,
       addTransactionCommand.actingAs,
       addTransactionCommand.from,
       addTransactionCommand.to,
       addTransactionCommand.value,
       addTransactionCommand.description,
       addTransactionCommand.metadata)
  )

  override final val CommandFormats = MessageFormats(
    "createZone"     -> Json.format[CreateZoneCommand],
    "joinZone"       -> Json.format[JoinZoneCommand],
    "quitZone"       -> Json.format[QuitZoneCommand],
    "changeZoneName" -> Json.format[ChangeZoneNameCommand],
    "createMember"   -> Json.format[CreateMemberCommand],
    "updateMember"   -> Json.format[UpdateMemberCommand],
    "createAccount"  -> Json.format[CreateAccountCommand],
    "updateAccount"  -> Json.format[UpdateAccountCommand],
    "addTransaction" -> AddTransactionCommandFormat
  )
}

sealed abstract class Response                                                  extends Message
sealed abstract class ZoneResponse                                              extends Response
case object EmptyZoneResponse                                                   extends ZoneResponse
final case class ErrorResponse(error: String)                                   extends ZoneResponse
sealed abstract class SuccessResponse                                           extends ZoneResponse
final case class CreateZoneResponse(zone: Zone)                                 extends SuccessResponse
final case class JoinZoneResponse(zone: Zone, connectedClients: Set[PublicKey]) extends SuccessResponse
case object QuitZoneResponse                                                    extends SuccessResponse
case object ChangeZoneNameResponse                                              extends SuccessResponse
final case class CreateMemberResponse(member: Member)                           extends SuccessResponse
case object UpdateMemberResponse                                                extends SuccessResponse
final case class CreateAccountResponse(account: Account)                        extends SuccessResponse
case object UpdateAccountResponse                                               extends SuccessResponse
final case class AddTransactionResponse(transaction: Transaction)               extends SuccessResponse

object SuccessResponse extends ResponseCompanion[SuccessResponse] {
  override final val ResponseFormats = MessageFormats(
    "createZone"     -> Json.format[CreateZoneResponse],
    "joinZone"       -> Json.format[JoinZoneResponse],
    "quitZone"       -> objectFormat(QuitZoneResponse),
    "changeZoneName" -> objectFormat(ChangeZoneNameResponse),
    "createMember"   -> Json.format[CreateMemberResponse],
    "updateMember"   -> objectFormat(UpdateMemberResponse),
    "createAccount"  -> Json.format[CreateAccountResponse],
    "updateAccount"  -> objectFormat(UpdateAccountResponse),
    "addTransaction" -> Json.format[AddTransactionResponse]
  )
}

sealed trait ResponseWithCorrelationIdOrNotification
final case class ResponseWithCorrelationId(response: Response, correlationId: Long)
    extends ResponseWithCorrelationIdOrNotification

sealed abstract class Notification extends Message with ResponseWithCorrelationIdOrNotification
sealed abstract class ZoneNotification extends Notification {
  def zoneId: ZoneId
}
final case class SupportedVersionsNotification(compatibleVersionNumbers: Set[Long]) extends Notification
case object KeepAliveNotification                                                   extends Notification
case object EmptyZoneNotification extends ZoneNotification {
  override def zoneId: ZoneId = sys.error("EmptyZoneNotification")
}
final case class ClientJoinedZoneNotification(zoneId: ZoneId, publicKey: PublicKey)     extends ZoneNotification
final case class ClientQuitZoneNotification(zoneId: ZoneId, publicKey: PublicKey)       extends ZoneNotification
final case class ZoneTerminatedNotification(zoneId: ZoneId)                             extends ZoneNotification
final case class ZoneNameChangedNotification(zoneId: ZoneId, name: Option[String])      extends ZoneNotification
final case class MemberCreatedNotification(zoneId: ZoneId, member: Member)              extends ZoneNotification
final case class MemberUpdatedNotification(zoneId: ZoneId, member: Member)              extends ZoneNotification
final case class AccountCreatedNotification(zoneId: ZoneId, account: Account)           extends ZoneNotification
final case class AccountUpdatedNotification(zoneId: ZoneId, account: Account)           extends ZoneNotification
final case class TransactionAddedNotification(zoneId: ZoneId, transaction: Transaction) extends ZoneNotification

object Notification extends NotificationCompanion[Notification] {
  override final val NotificationFormats = MessageFormats(
    "supportedVersions" -> Json.format[SupportedVersionsNotification],
    "keepAlive"         -> objectFormat(KeepAliveNotification),
    "clientJoinedZone"  -> Json.format[ClientJoinedZoneNotification],
    "clientQuitZone"    -> Json.format[ClientQuitZoneNotification],
    "zoneTerminated"    -> Json.format[ZoneTerminatedNotification],
    "zoneNameChanged"   -> Json.format[ZoneNameChangedNotification],
    "memberCreated"     -> Json.format[MemberCreatedNotification],
    "memberUpdated"     -> Json.format[MemberUpdatedNotification],
    "accountCreated"    -> Json.format[AccountCreatedNotification],
    "accountUpdated"    -> Json.format[AccountUpdatedNotification],
    "transactionAdded"  -> Json.format[TransactionAddedNotification]
  )
}
