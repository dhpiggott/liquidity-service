package models

import com.pellucid.sealerate
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

sealed abstract class MessageTypeName(val typeName: String)

// TODO: Rename as Commands and Events?
// TODO: Responses

sealed trait OutboundMessage
sealed trait OutboundMessageTypeName extends MessageTypeName
sealed trait OutboundZoneMessage extends OutboundMessage {
  val zoneId: ZoneId
}

case class ConnectionNumber(connectionNumber: Int) extends OutboundMessage
case object ConnectionNumberTypeName extends MessageTypeName("connectionNumber") with OutboundMessageTypeName

case class Heartbeat(dateTime: DateTime) extends OutboundMessage
case object HeartbeatTypeName extends MessageTypeName("heartbeat") with OutboundMessageTypeName

case class ZoneCreated(zoneId: ZoneId) extends OutboundZoneMessage
case object ZoneCreatedTypeName extends MessageTypeName("zoneCreated") with OutboundMessageTypeName

case class MemberJoinedZone(zoneId: ZoneId, memberId: MemberId) extends OutboundZoneMessage
case object MemberJoinedZoneTypeName extends MessageTypeName("memberJoinedZone") with OutboundMessageTypeName

case class MemberQuitZone(zoneId: ZoneId, memberId: MemberId) extends OutboundZoneMessage
case object MemberQuitZoneTypeName extends MessageTypeName("memberQuitZone") with OutboundMessageTypeName

// TODO: Signing
case class ZoneState(zoneId: ZoneId, zone: Zone) extends OutboundZoneMessage
case object ZoneStateTypeName extends MessageTypeName("zoneState") with OutboundMessageTypeName

case class ZoneEmpty(zoneId: ZoneId) extends OutboundZoneMessage
case object ZoneEmptyTypeName extends MessageTypeName("zoneEmpty") with OutboundMessageTypeName

object OutboundMessage {


  implicit val outboundMessageWrites: Writes[OutboundMessage] = (
    (__ \ "type").write[String] and
      (__ \ "data").write[JsValue]
    )((message: OutboundMessage) => message match {
    case message: ConnectionNumber => (ConnectionNumberTypeName.typeName, Json.toJson(message)(Json.writes[ConnectionNumber]))
    case message: Heartbeat => (HeartbeatTypeName.typeName, Json.toJson(message)(Json.writes[Heartbeat]))
    case message: ZoneCreated => (ZoneCreatedTypeName.typeName, Json.toJson(message)(Json.writes[ZoneCreated]))
    case message: MemberJoinedZone => (MemberJoinedZoneTypeName.typeName, Json.toJson(message)(Json.writes[MemberJoinedZone]))
    case message: MemberQuitZone => (MemberQuitZoneTypeName.typeName, Json.toJson(message)(Json.writes[MemberQuitZone]))
    case message: ZoneState => (ZoneStateTypeName.typeName, Json.toJson(message)(Json.writes[ZoneState]))
    case message: ZoneEmpty => (ZoneEmptyTypeName.typeName, Json.toJson(message)(Json.writes[ZoneEmpty]))
  })

}

sealed trait InboundMessage
sealed trait InboundMessageTypeName extends MessageTypeName
sealed trait InboundZoneMessage extends InboundMessage {
  val zoneId: ZoneId
}

case class CreateZone(name: String, zoneType: String) extends InboundMessage
case object CreateZoneTypeName extends MessageTypeName("createZone") with InboundMessageTypeName

case class JoinZone(zoneId: ZoneId) extends InboundZoneMessage
case object JoinZoneTypeName extends MessageTypeName("joinZone") with InboundMessageTypeName

case class QuitZone(zoneId: ZoneId) extends InboundZoneMessage
case object QuitZoneTypeName extends MessageTypeName("quitZone") with InboundMessageTypeName

case class SetZoneName(zoneId: ZoneId, name: String) extends InboundZoneMessage
case object SetZoneNameTypeName extends MessageTypeName("setZoneName") with InboundMessageTypeName

case class CreateMember(zoneId: ZoneId, member: Member) extends InboundZoneMessage
case object CreateMemberTypeName extends MessageTypeName("createMember") with InboundMessageTypeName

case class UpdateMember(zoneId: ZoneId, memberId: MemberId, member: Member) extends InboundZoneMessage
case object UpdateMemberTypeName extends MessageTypeName("updateMember") with InboundMessageTypeName

case class DeleteMember(zoneId: ZoneId, memberId: MemberId) extends InboundZoneMessage
case object DeleteMemberTypeName extends MessageTypeName("deleteMember") with InboundMessageTypeName

case class CreateAccount(zoneId: ZoneId, account: Account) extends InboundZoneMessage
case object CreateAccountTypeName extends MessageTypeName("createAccount") with InboundMessageTypeName

case class UpdateAccount(zoneId: ZoneId, accountId: AccountId, account: Account) extends InboundZoneMessage
case object UpdateAccountTypeName extends MessageTypeName("updateAccount") with InboundMessageTypeName

case class DeleteAccount(zoneId: ZoneId, accountId: AccountId) extends InboundZoneMessage
case object DeleteAccountTypeName extends MessageTypeName("deleteAccount") with InboundMessageTypeName

case class AddTransaction(zoneId: ZoneId, transaction: Transaction) extends InboundZoneMessage
case object AddTransactionTypeName extends MessageTypeName("addTransaction") with InboundMessageTypeName

// TODO: Signing
case class RestoreZone(zoneId: ZoneId, zone: Zone) extends InboundZoneMessage
case object RestoreZoneTypeName extends MessageTypeName("restoreZone") with InboundMessageTypeName

object InboundMessage {

  val inboundMessageTypes = sealerate.values[InboundMessageTypeName]

  implicit val inboundMessageReads: Reads[InboundMessage] = (
    (__ \ "type").read[String](Reads.verifying[String](typeName => inboundMessageTypes.exists(messageType => messageType.typeName == typeName))) and
      (__ \ "data").read[JsValue]
    )((messageType, messageData) => messageType match {
    case CreateZoneTypeName.typeName => messageData.as(Json.reads[CreateZone])
    case JoinZoneTypeName.typeName => messageData.as(Json.reads[JoinZone])
    case QuitZoneTypeName.typeName => messageData.as(Json.reads[QuitZone])
    case SetZoneNameTypeName.typeName => messageData.as(Json.reads[SetZoneName])
    case CreateMemberTypeName.typeName => messageData.as(Json.reads[CreateMember])
    case UpdateMemberTypeName.typeName => messageData.as(Json.reads[UpdateMember])
    case DeleteMemberTypeName.typeName => messageData.as(Json.reads[DeleteMember])
    case CreateAccountTypeName.typeName => messageData.as(Json.reads[CreateAccount])
    case UpdateAccountTypeName.typeName => messageData.as(Json.reads[UpdateAccount])
    case DeleteAccountTypeName.typeName => messageData.as(Json.reads[DeleteAccount])
    case AddTransactionTypeName.typeName => messageData.as(Json.reads[AddTransaction])
    case RestoreZoneTypeName.typeName => messageData.as(Json.reads[RestoreZone])
  })

}