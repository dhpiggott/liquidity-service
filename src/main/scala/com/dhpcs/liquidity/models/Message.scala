package com.dhpcs.liquidity.models

import com.dhpcs.jsonrpc._
import play.api.libs.json._

// TODO: Review https://github.com/zilverline/event-sourced-blog-example/blob/master/app/events/PostEvents.scala#L60-L65
// and https://github.com/zilverline/event-sourced-blog-example/blob/master/app/eventstore/JsonMapping.scala to see if
// this can be improved.

sealed trait Message

sealed trait Command extends Message

sealed abstract class CommandMethodName(val name: String)

sealed trait ZoneCommand extends Command {

  val zoneId: ZoneId

}

case class CreateZone(name: String, zoneType: String) extends Command
case object CreateZoneMethodName extends CommandMethodName("createZone")

case class JoinZone(zoneId: ZoneId) extends ZoneCommand
case object JoinZoneMethodName extends CommandMethodName("joinZone")

// TODO: Signing - use https://github.com/sbt/sbt-pgp?
case class RestoreZone(zoneId: ZoneId, zone: Zone) extends ZoneCommand
case object RestoreZoneMethodName extends CommandMethodName("restoreZone")

case class QuitZone(zoneId: ZoneId) extends ZoneCommand
case object QuitZoneMethodName extends CommandMethodName("quitZone")

case class SetZoneName(zoneId: ZoneId, name: String) extends ZoneCommand
case object SetZoneNameMethodName extends CommandMethodName("setZoneName")

case class CreateMember(zoneId: ZoneId, member: Member) extends ZoneCommand
case object CreateMemberMethodName extends CommandMethodName("createMember")

case class UpdateMember(zoneId: ZoneId, memberId: MemberId, member: Member) extends ZoneCommand
case object UpdateMemberMethodName extends CommandMethodName("updateMember")

case class DeleteMember(zoneId: ZoneId, memberId: MemberId) extends ZoneCommand
case object DeleteMemberMethodName extends CommandMethodName("deleteMember")

case class CreateAccount(zoneId: ZoneId, account: Account) extends ZoneCommand
case object CreateAccountMethodName extends CommandMethodName("createAccount")

case class UpdateAccount(zoneId: ZoneId, accountId: AccountId, account: Account) extends ZoneCommand
case object UpdateAccountMethodName extends CommandMethodName("updateAccount")

case class DeleteAccount(zoneId: ZoneId, accountId: AccountId) extends ZoneCommand
case object DeleteAccountMethodName extends CommandMethodName("deleteAccount")

case class AddTransaction(zoneId: ZoneId, transaction: Transaction) extends ZoneCommand
case object AddTransactionMethodName extends CommandMethodName("addTransaction")

object Command {

  def readCommand(jsonRpcRequestMessage: JsonRpcRequestMessage): Option[JsResult[Command]] = {
    val jsObject = jsonRpcRequestMessage.params.right.get
    jsonRpcRequestMessage.method match {
      case CreateZoneMethodName.name => Some(Json.fromJson(jsObject)(Json.reads[CreateZone]))
      case JoinZoneMethodName.name => Some(Json.fromJson(jsObject)(Json.reads[JoinZone]))
      case RestoreZoneMethodName.name => Some(Json.fromJson(jsObject)(Json.reads[RestoreZone]))
      case QuitZoneMethodName.name => Some(Json.fromJson(jsObject)(Json.reads[QuitZone]))
      case SetZoneNameMethodName.name => Some(Json.fromJson(jsObject)(Json.reads[SetZoneName]))
      case CreateMemberMethodName.name => Some(Json.fromJson(jsObject)(Json.reads[CreateMember]))
      case UpdateMemberMethodName.name => Some(Json.fromJson(jsObject)(Json.reads[UpdateMember]))
      case DeleteMemberMethodName.name => Some(Json.fromJson(jsObject)(Json.reads[DeleteMember]))
      case CreateAccountMethodName.name => Some(Json.fromJson(jsObject)(Json.reads[CreateAccount]))
      case DeleteAccountMethodName.name => Some(Json.fromJson(jsObject)(Json.reads[DeleteAccount]))
      case AddTransactionMethodName.name => Some(Json.fromJson(jsObject)(Json.reads[AddTransaction]))
      case _ => None
    }
  }

  def writeCommand(command: Command, id: Either[String, Int]): JsonRpcRequestMessage = {
    val (method, jsValue) = command match {
      case command: CreateZone => (CreateZoneMethodName.name, Json.toJson(command)(Json.writes[CreateZone]))
      case command: JoinZone => (JoinZoneMethodName.name, Json.toJson(command)(Json.writes[JoinZone]))
      case command: RestoreZone => (RestoreZoneMethodName.name, Json.toJson(command)(Json.writes[RestoreZone]))
      case command: QuitZone => (QuitZoneMethodName.name, Json.toJson(command)(Json.writes[QuitZone]))
      case command: SetZoneName => (SetZoneNameMethodName.name, Json.toJson(command)(Json.writes[SetZoneName]))
      case command: CreateMember => (CreateMemberMethodName.name, Json.toJson(command)(Json.writes[CreateMember]))
      case command: UpdateMember => (UpdateMemberMethodName.name, Json.toJson(command)(Json.writes[UpdateMember]))
      case command: DeleteMember => (DeleteMemberMethodName.name, Json.toJson(command)(Json.writes[DeleteMember]))
      case command: CreateAccount => (CreateAccountMethodName.name, Json.toJson(command)(Json.writes[CreateAccount]))
      case command: UpdateAccount => (UpdateAccountMethodName.name, Json.toJson(command)(Json.writes[UpdateAccount]))
      case command: DeleteAccount => (DeleteAccountMethodName.name, Json.toJson(command)(Json.writes[DeleteAccount]))
      case command: AddTransaction => (AddTransactionMethodName.name, Json.toJson(command)(Json.writes[AddTransaction]))
    }
    // TODO: It would be nice to get an OWrites and use that directly to avoid the cast.
    JsonRpcRequestMessage(method, Right(jsValue.asInstanceOf[JsObject]), id)
  }

}

sealed trait CommandResponse extends Message

case class CommandErrorResponse(code: Int, message: String, data: Option[JsValue]) extends CommandResponse

sealed trait CommandResultResponse extends CommandResponse

case class ZoneCreated(zoneId: ZoneId) extends CommandResultResponse

case class ZoneJoined(zone: Option[Zone]) extends CommandResultResponse

object CommandResponse {

  def readCommandResponse(jsonRpcResponseMessage: JsonRpcResponseMessage, method: String): JsResult[CommandResponse] =
    jsonRpcResponseMessage.eitherErrorOrResult.fold(
      error => JsSuccess(CommandErrorResponse(error.code, error.message, error.data)),
      result => {
        method match {
          case CreateZoneMethodName.name => Json.fromJson(result)(Json.reads[ZoneCreated])
          case JoinZoneMethodName.name => Json.fromJson(result)(Json.reads[ZoneJoined])
        }
      }
    )

  def writeCommandResponse(commandResponse: CommandResponse, id: Either[String, Int]): JsonRpcResponseMessage = {
    val eitherErrorOrResult = commandResponse match {
      case CommandErrorResponse(code, message, data) => Left(
        JsonRpcResponseError(code, message, data)
      )
      case commandResultResponse: CommandResultResponse => commandResultResponse match {
        case commandResponse: ZoneCreated => Right(Json.toJson(commandResponse)(Json.writes[ZoneCreated]))
        case commandResponse: ZoneJoined => Right(Json.toJson(commandResponse)(Json.writes[ZoneJoined]))
      }
    }
    JsonRpcResponseMessage(eitherErrorOrResult, id)
  }

}

sealed trait Notification extends Message

sealed abstract class NotificationMethodName(val name: String)

sealed trait ZoneNotification extends Notification {

  val zoneId: ZoneId

}

// TODO: Signing - use https://github.com/sbt/sbt-pgp?
case class ZoneState(zoneId: ZoneId, zone: Zone) extends ZoneNotification
case object ZoneStateMethodName extends NotificationMethodName("zoneState")

case class ZoneTerminated(zoneId: ZoneId) extends ZoneNotification
case object ZoneTerminatedMethodName extends NotificationMethodName("zoneTerminated")

case class MemberJoinedZone(zoneId: ZoneId, memberId: MemberId) extends ZoneNotification
case object MemberJoinedZoneMethodName extends NotificationMethodName("memberJoinedZone")

case class MemberQuitZone(zoneId: ZoneId, memberId: MemberId) extends ZoneNotification
case object MemberQuitZoneMethodName extends NotificationMethodName("memberQuitZone")

object Notification {

  def readNotification(jsonRpcNotificationMessage: JsonRpcNotificationMessage): Option[JsResult[Notification]] = {
    val jsObject = jsonRpcNotificationMessage.params.right.get
    jsonRpcNotificationMessage.method match {
      case ZoneStateMethodName.name => Some(Json.fromJson(jsObject)(Json.reads[ZoneState]))
      case ZoneTerminatedMethodName.name => Some(Json.fromJson(jsObject)(Json.reads[ZoneTerminated]))
      case MemberJoinedZoneMethodName.name => Some(Json.fromJson(jsObject)(Json.reads[MemberJoinedZone]))
      case MemberQuitZoneMethodName.name => Some(Json.fromJson(jsObject)(Json.reads[MemberQuitZone]))
      case _ => None
    }
  }

  def writeNotification(notification: Notification): JsonRpcNotificationMessage = {
    val (method, jsValue) = notification match {
      case notification: ZoneState => (ZoneStateMethodName.name, Json.toJson(notification)(Json.writes[ZoneState]))
      case notification: ZoneTerminated => (ZoneTerminatedMethodName.name, Json.toJson(notification)(Json.writes[ZoneTerminated]))
      case notification: MemberJoinedZone => (MemberJoinedZoneMethodName.name, Json.toJson(notification)(Json.writes[MemberJoinedZone]))
      case notification: MemberQuitZone => (MemberQuitZoneMethodName.name, Json.toJson(notification)(Json.writes[MemberQuitZone]))
    }
    // TODO: It would be nice to get an OWrites and use that directly to avoid the cast.
    JsonRpcNotificationMessage(method, Right(jsValue.asInstanceOf[JsObject]))
  }

}