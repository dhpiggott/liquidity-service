package com.dhpcs.liquidity.ws.protocol

import com.dhpcs.jsonrpc.Message.{MessageFormats, objectFormat}
import com.dhpcs.jsonrpc.{CommandCompanion, NotificationCompanion, ResponseCompanion}
import com.dhpcs.liquidity.model._
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads.min
import play.api.libs.json._

sealed trait Message

sealed trait Command extends Message
sealed trait ZoneCommand extends Command {
  val zoneId: ZoneId
}

case class CreateZoneCommand(equityOwnerPublicKey: PublicKey,
                             equityOwnerName: Option[String],
                             equityOwnerMetadata: Option[JsObject],
                             equityAccountName: Option[String],
                             equityAccountMetadata: Option[JsObject],
                             name: Option[String] = None,
                             metadata: Option[JsObject] = None)
    extends Command
case class JoinZoneCommand(zoneId: ZoneId)                             extends ZoneCommand
case class QuitZoneCommand(zoneId: ZoneId)                             extends ZoneCommand
case class ChangeZoneNameCommand(zoneId: ZoneId, name: Option[String]) extends ZoneCommand
case class CreateMemberCommand(zoneId: ZoneId,
                               ownerPublicKey: PublicKey,
                               name: Option[String] = None,
                               metadata: Option[JsObject] = None)
    extends ZoneCommand
case class UpdateMemberCommand(zoneId: ZoneId, member: Member) extends ZoneCommand
case class CreateAccountCommand(zoneId: ZoneId,
                                ownerMemberIds: Set[MemberId],
                                name: Option[String] = None,
                                metadata: Option[JsObject] = None)
    extends ZoneCommand
case class UpdateAccountCommand(zoneId: ZoneId, account: Account) extends ZoneCommand
case class AddTransactionCommand(zoneId: ZoneId,
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

sealed trait Response                                                     extends Message
sealed trait ResultResponse                                               extends Response
case class CreateZoneResponse(zone: Zone)                                 extends ResultResponse
case class JoinZoneResponse(zone: Zone, connectedClients: Set[PublicKey]) extends ResultResponse
case object QuitZoneResponse                                              extends ResultResponse
case object ChangeZoneNameResponse                                        extends ResultResponse
case class CreateMemberResponse(member: Member)                           extends ResultResponse
case object UpdateMemberResponse                                          extends ResultResponse
case class CreateAccountResponse(account: Account)                        extends ResultResponse
case object UpdateAccountResponse                                         extends ResultResponse
case class AddTransactionResponse(transaction: Transaction)               extends ResultResponse

object Response extends ResponseCompanion[ResultResponse] {
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

  implicit final val ResultResponseFormat: Format[ResultResponse] = Format[ResultResponse](
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
    Writes(resultResponse => {
      val (_, classWrites) = ResponseFormats
      classWrites.get(resultResponse.getClass) match {
        case None => sys.error(s"No format found for ${resultResponse.getClass}")
        case Some((method, writes)) =>
          Json.obj(
            "method" -> method,
            "value"  -> writes.asInstanceOf[Format[ResultResponse]].writes(resultResponse)
          )
      }
    })
  )

}

sealed trait Notification extends Message
sealed trait ZoneNotification extends Notification {
  val zoneId: ZoneId
}
case class SupportedVersionsNotification(compatibleVersionNumbers: Set[Int])      extends Notification
case object KeepAliveNotification                                                 extends Notification
case class ClientJoinedZoneNotification(zoneId: ZoneId, publicKey: PublicKey)     extends ZoneNotification
case class ClientQuitZoneNotification(zoneId: ZoneId, publicKey: PublicKey)       extends ZoneNotification
case class ZoneTerminatedNotification(zoneId: ZoneId)                             extends ZoneNotification
case class ZoneNameChangedNotification(zoneId: ZoneId, name: Option[String])      extends ZoneNotification
case class MemberCreatedNotification(zoneId: ZoneId, member: Member)              extends ZoneNotification
case class MemberUpdatedNotification(zoneId: ZoneId, member: Member)              extends ZoneNotification
case class AccountCreatedNotification(zoneId: ZoneId, account: Account)           extends ZoneNotification
case class AccountUpdatedNotification(zoneId: ZoneId, account: Account)           extends ZoneNotification
case class TransactionAddedNotification(zoneId: ZoneId, transaction: Transaction) extends ZoneNotification

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
