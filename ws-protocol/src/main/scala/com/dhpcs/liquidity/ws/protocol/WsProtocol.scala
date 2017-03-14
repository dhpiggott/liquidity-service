package com.dhpcs.liquidity.ws.protocol

import com.dhpcs.jsonrpc.Message.{MessageFormats, objectFormat}
import com.dhpcs.jsonrpc.{CommandCompanion, NotificationCompanion, ResponseCompanion}
import com.dhpcs.liquidity.model._
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads.min
import play.api.libs.json._

sealed abstract class Message

sealed abstract class Command extends Message
sealed abstract class ZoneCommand extends Command {
  val zoneId: ZoneId
}

final case class CreateZoneCommand(equityOwnerPublicKey: PublicKey,
                                   equityOwnerName: Option[String],
                                   equityOwnerMetadata: Option[JsObject],
                                   equityAccountName: Option[String],
                                   equityAccountMetadata: Option[JsObject],
                                   name: Option[String] = None,
                                   metadata: Option[JsObject] = None)
    extends Command
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

  private val addTransactionCommandFormat: Format[AddTransactionCommand] = (
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
    "addTransaction" -> addTransactionCommandFormat
  )

  implicit final val CommandFormat: Format[Command] = Format[Command](
    Reads(json => {
      val (methodReads, _) = CommandFormats
      for {
        jsObject <- json.validate[JsObject]
        method   <- (jsObject \ "method").validate[String]
        value    <- (jsObject \ "value").validate[JsValue]
        result <- methodReads.get(method) match {
          case None        => JsError(s"unknown method $method")
          case Some(reads) => reads.reads(value)
        }
      } yield result
    }),
    Writes(command => {
      val (_, classWrites) = CommandFormats
      classWrites.get(command.getClass) match {
        case None => sys.error(s"No format found for ${command.getClass}")
        case Some((method, writes)) =>
          Json.obj(
            "method" -> method,
            "value"  -> writes.asInstanceOf[OFormat[Command]].writes(command)
          )
      }
    })
  )

}

sealed abstract class Response                                                  extends Message
final case class CreateZoneResponse(zone: Zone)                                 extends Response
final case class JoinZoneResponse(zone: Zone, connectedClients: Set[PublicKey]) extends Response
case object QuitZoneResponse                                                    extends Response
case object ChangeZoneNameResponse                                              extends Response
final case class CreateMemberResponse(member: Member)                           extends Response
case object UpdateMemberResponse                                                extends Response
final case class CreateAccountResponse(account: Account)                        extends Response
case object UpdateAccountResponse                                               extends Response
final case class AddTransactionResponse(transaction: Transaction)               extends Response

object Response extends ResponseCompanion[Response] {

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

  implicit final val ResponseFormat: Format[Response] = Format[Response](
    Reads(json => {
      val (methodReads, _) = ResponseFormats
      for {
        jsObject <- json.validate[JsObject]
        method   <- (jsObject \ "method").validate[String]
        value    <- (jsObject \ "value").validate[JsValue]
        result <- methodReads.get(method) match {
          case None        => JsError(s"unknown method $method")
          case Some(reads) => reads.reads(value)
        }
      } yield result
    }),
    Writes(response => {
      val (_, classWrites) = ResponseFormats
      classWrites.get(response.getClass) match {
        case None => sys.error(s"No format found for ${response.getClass}")
        case Some((method, writes)) =>
          Json.obj(
            "method" -> method,
            "value"  -> writes.asInstanceOf[Format[Response]].writes(response)
          )
      }
    })
  )

}

sealed abstract class Notification extends Message
sealed abstract class ZoneNotification extends Notification {
  val zoneId: ZoneId
}
final case class SupportedVersionsNotification(compatibleVersionNumbers: Set[Int])      extends Notification
case object KeepAliveNotification                                                       extends Notification
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

  implicit final val NotificationFormat: Format[Notification] = Format[Notification](
    Reads(json => {
      val (methodReads, _) = NotificationFormats
      for {
        jsObject <- json.validate[JsObject]
        method   <- (jsObject \ "method").validate[String]
        value    <- (jsObject \ "value").validate[JsValue]
        result <- methodReads.get(method) match {
          case None        => JsError(s"unknown method $method")
          case Some(reads) => reads.reads(value)
        }
      } yield result
    }),
    Writes(notification => {
      val (_, classWrites) = NotificationFormats
      classWrites.get(notification.getClass) match {
        case None => sys.error(s"No format found for ${notification.getClass}")
        case Some((method, writes)) =>
          Json.obj(
            "method" -> method,
            "value"  -> writes.asInstanceOf[Writes[Notification]].writes(notification)
          )
      }
    })
  )

}
