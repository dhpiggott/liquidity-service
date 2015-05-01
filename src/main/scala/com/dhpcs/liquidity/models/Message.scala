package com.dhpcs.liquidity.models

import com.pellucid.sealerate
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

sealed abstract class MessageTypeName(val typeName: String)

sealed trait Event
sealed trait EventTypeName extends MessageTypeName
sealed trait ZoneEvent extends Event {
  val zoneId: ZoneId
}

case class ConnectionNumber(connectionNumber: Int) extends Event
case object ConnectionNumberTypeName extends MessageTypeName("connectionNumber") with EventTypeName

case class Heartbeat(dateTime: DateTime) extends Event
case object HeartbeatTypeName extends MessageTypeName("heartbeat") with EventTypeName

case class ZoneCreated(zoneId: ZoneId) extends ZoneEvent
case object ZoneCreatedTypeName extends MessageTypeName("zoneCreated") with EventTypeName

// TODO: Signing - use https://github.com/sbt/sbt-pgp?
case class ZoneState(zoneId: ZoneId, zone: Zone) extends ZoneEvent
case object ZoneStateTypeName extends MessageTypeName("zoneState") with EventTypeName

case class ZoneEmpty(zoneId: ZoneId) extends ZoneEvent
case object ZoneEmptyTypeName extends MessageTypeName("zoneEmpty") with EventTypeName

case class ZoneTerminated(zoneId: ZoneId) extends ZoneEvent
case object ZoneTerminatedTypeName extends MessageTypeName("zoneTerminated") with EventTypeName

case class MemberJoinedZone(zoneId: ZoneId, memberId: MemberId) extends ZoneEvent
case object MemberJoinedZoneTypeName extends MessageTypeName("memberJoinedZone") with EventTypeName

case class MemberQuitZone(zoneId: ZoneId, memberId: MemberId) extends ZoneEvent
case object MemberQuitZoneTypeName extends MessageTypeName("memberQuitZone") with EventTypeName

object Event {

  def eventTypes = sealerate.values[EventTypeName]

  implicit val eventReads: Reads[Event] = (
    (__ \ "type").read[String](Reads.verifying[String](typeName => eventTypes.exists(eventType => eventType.typeName == typeName))) and
      (__ \ "data").read[JsValue]
    )((eventType, eventData) => eventType match {
    case ConnectionNumberTypeName.typeName => eventData.as(Json.reads[ConnectionNumber])
    case HeartbeatTypeName.typeName => eventData.as(Json.reads[Heartbeat])
    case ZoneCreatedTypeName.typeName => eventData.as(Json.reads[ZoneCreated])
    case ZoneStateTypeName.typeName => eventData.as(Json.reads[ZoneState])
    case ZoneEmptyTypeName.typeName => eventData.as(Json.reads[ZoneEmpty])
    case ZoneTerminatedTypeName.typeName => eventData.as(Json.reads[ZoneTerminated])
    case MemberJoinedZoneTypeName.typeName => eventData.as(Json.reads[MemberJoinedZone])
    case MemberQuitZoneTypeName.typeName => eventData.as(Json.reads[MemberQuitZone])
  })

  implicit val eventWrites: Writes[Event] = (
    (__ \ "type").write[String] and
      (__ \ "data").write[JsValue]
    )((event: Event) => event match {
    case message: ConnectionNumber => (ConnectionNumberTypeName.typeName, Json.toJson(message)(Json.writes[ConnectionNumber]))
    case message: Heartbeat => (HeartbeatTypeName.typeName, Json.toJson(message)(Json.writes[Heartbeat]))
    case message: ZoneCreated => (ZoneCreatedTypeName.typeName, Json.toJson(message)(Json.writes[ZoneCreated]))
    case message: ZoneState => (ZoneStateTypeName.typeName, Json.toJson(message)(Json.writes[ZoneState]))
    case message: ZoneEmpty => (ZoneEmptyTypeName.typeName, Json.toJson(message)(Json.writes[ZoneEmpty]))
    case message: ZoneTerminated => (ZoneTerminatedTypeName.typeName, Json.toJson(message)(Json.writes[ZoneTerminated]))
    case message: MemberJoinedZone => (MemberJoinedZoneTypeName.typeName, Json.toJson(message)(Json.writes[MemberJoinedZone]))
    case message: MemberQuitZone => (MemberQuitZoneTypeName.typeName, Json.toJson(message)(Json.writes[MemberQuitZone]))
  })

}

sealed trait Command
sealed trait CommandTypeName extends MessageTypeName
sealed trait ZoneCommand extends Command {
  val zoneId: ZoneId
}

case class CreateZone(name: String, zoneType: String) extends Command
case object CreateZoneTypeName extends MessageTypeName("createZone") with CommandTypeName

case class JoinZone(zoneId: ZoneId) extends ZoneCommand
case object JoinZoneTypeName extends MessageTypeName("joinZone") with CommandTypeName

// TODO: Signing
case class RestoreZone(zoneId: ZoneId, zone: Zone) extends ZoneCommand
case object RestoreZoneTypeName extends MessageTypeName("restoreZone") with CommandTypeName

case class QuitZone(zoneId: ZoneId) extends ZoneCommand
case object QuitZoneTypeName extends MessageTypeName("quitZone") with CommandTypeName

case class SetZoneName(zoneId: ZoneId, name: String) extends ZoneCommand
case object SetZoneNameTypeName extends MessageTypeName("setZoneName") with CommandTypeName

case class CreateMember(zoneId: ZoneId, member: Member) extends ZoneCommand
case object CreateMemberTypeName extends MessageTypeName("createMember") with CommandTypeName

case class UpdateMember(zoneId: ZoneId, memberId: MemberId, member: Member) extends ZoneCommand
case object UpdateMemberTypeName extends MessageTypeName("updateMember") with CommandTypeName

case class DeleteMember(zoneId: ZoneId, memberId: MemberId) extends ZoneCommand
case object DeleteMemberTypeName extends MessageTypeName("deleteMember") with CommandTypeName

case class CreateAccount(zoneId: ZoneId, account: Account) extends ZoneCommand
case object CreateAccountTypeName extends MessageTypeName("createAccount") with CommandTypeName

case class UpdateAccount(zoneId: ZoneId, accountId: AccountId, account: Account) extends ZoneCommand
case object UpdateAccountTypeName extends MessageTypeName("updateAccount") with CommandTypeName

case class DeleteAccount(zoneId: ZoneId, accountId: AccountId) extends ZoneCommand
case object DeleteAccountTypeName extends MessageTypeName("deleteAccount") with CommandTypeName

case class AddTransaction(zoneId: ZoneId, transaction: Transaction) extends ZoneCommand
case object AddTransactionTypeName extends MessageTypeName("addTransaction") with CommandTypeName

object Command {

  def commandTypes = sealerate.values[CommandTypeName]

  implicit val commandReads: Reads[Command] = (
    (__ \ "type").read[String](Reads.verifying[String](typeName => commandTypes.exists(commandType => commandType.typeName == typeName))) and
      (__ \ "data").read[JsValue]
    )((commandType, commandData) => commandType match {
    case CreateZoneTypeName.typeName => commandData.as(Json.reads[CreateZone])
    case JoinZoneTypeName.typeName => commandData.as(Json.reads[JoinZone])
    case RestoreZoneTypeName.typeName => commandData.as(Json.reads[RestoreZone])
    case QuitZoneTypeName.typeName => commandData.as(Json.reads[QuitZone])
    case SetZoneNameTypeName.typeName => commandData.as(Json.reads[SetZoneName])
    case CreateMemberTypeName.typeName => commandData.as(Json.reads[CreateMember])
    case UpdateMemberTypeName.typeName => commandData.as(Json.reads[UpdateMember])
    case DeleteMemberTypeName.typeName => commandData.as(Json.reads[DeleteMember])
    case CreateAccountTypeName.typeName => commandData.as(Json.reads[CreateAccount])
    case UpdateAccountTypeName.typeName => commandData.as(Json.reads[UpdateAccount])
    case DeleteAccountTypeName.typeName => commandData.as(Json.reads[DeleteAccount])
    case AddTransactionTypeName.typeName => commandData.as(Json.reads[AddTransaction])
  })

  implicit val commandWrites: Writes[Command] = (
    (__ \ "type").write[String] and
      (__ \ "data").write[JsValue]
    )((command: Command) => command match {
    case message: CreateZone => (CreateZoneTypeName.typeName, Json.toJson(message)(Json.writes[CreateZone]))
    case message: JoinZone => (JoinZoneTypeName.typeName, Json.toJson(message)(Json.writes[JoinZone]))
    case message: RestoreZone => (RestoreZoneTypeName.typeName, Json.toJson(message)(Json.writes[RestoreZone]))
    case message: QuitZone => (QuitZoneTypeName.typeName, Json.toJson(message)(Json.writes[QuitZone]))
    case message: SetZoneName => (SetZoneNameTypeName.typeName, Json.toJson(message)(Json.writes[SetZoneName]))
    case message: CreateMember => (CreateMemberTypeName.typeName, Json.toJson(message)(Json.writes[CreateMember]))
    case message: UpdateMember => (UpdateMemberTypeName.typeName, Json.toJson(message)(Json.writes[UpdateMember]))
    case message: DeleteMember => (DeleteMemberTypeName.typeName, Json.toJson(message)(Json.writes[DeleteMember]))
    case message: CreateAccount => (CreateAccountTypeName.typeName, Json.toJson(message)(Json.writes[CreateAccount]))
    case message: UpdateAccount => (UpdateAccountTypeName.typeName, Json.toJson(message)(Json.writes[UpdateAccount]))
    case message: DeleteAccount => (DeleteAccountTypeName.typeName, Json.toJson(message)(Json.writes[DeleteAccount]))
    case message: AddTransaction => (AddTransactionTypeName.typeName, Json.toJson(message)(Json.writes[AddTransaction]))
  })

}
