package com.dhpcs.liquidity.models

import com.dhpcs.jsonrpc._
import com.dhpcs.liquidity.models.Message.MethodFormats
import play.api.libs.json._

import scala.reflect.ClassTag

sealed trait Message

object Message {

  abstract class MethodFormat[A](methodAndFormatOrObject: (String, Either[A, Format[A]]))
                                (implicit val classTag: ClassTag[A]) {

    val (methodName, formatOrObject) = methodAndFormatOrObject

    def fromJson(jsValue: JsValue) = formatOrObject.fold(
      commandResponse => JsSuccess(commandResponse),
      format => format.reads(jsValue)
    )

    def matchesInstance(o: Any) = classTag.runtimeClass.isInstance(o)

    def toJson(o: Any) = formatOrObject.fold(
      _ => Json.obj(),
      format => format.writes(o.asInstanceOf[A])
    )

  }

  implicit class MethodFormatFormat[A](methodAndFormat: (String, Format[A]))(implicit classTag: ClassTag[A])
    extends MethodFormat(methodAndFormat._1, Right(methodAndFormat._2))(classTag)

  implicit class MethodFormatObject[A](methodAndObject: (String, A))(implicit classTag: ClassTag[A])
    extends MethodFormat(methodAndObject._1, Left(methodAndObject._2))(classTag)

  object MethodFormats {

    def apply[A](methodFormats: MethodFormat[_ <: A]*) = {
      val methodNames = methodFormats.map(_.methodName)
      require(
        methodNames == methodNames.distinct,
        "Duplicate method names: " + methodNames.mkString(", ")
      )
      val overlappingTypes = methodFormats.combinations(2).filter {
        case Seq(first, second) => first.classTag.runtimeClass isAssignableFrom second.classTag.runtimeClass
      }
      require(
        overlappingTypes.isEmpty,
        "Overlapping types: " + overlappingTypes.map {
          case Seq(first, second) => first.classTag + " is assignable from " + second.classTag
        }.mkString(", ")
      )
      methodFormats
    }

  }

}

sealed trait Command extends Message

sealed trait ZoneCommand extends Command {

  val zoneId: ZoneId

}

case class CreateZone(name: String, zoneType: String) extends Command

case class JoinZone(zoneId: ZoneId) extends ZoneCommand

case class QuitZone(zoneId: ZoneId) extends ZoneCommand

case class SetZoneName(zoneId: ZoneId, name: String) extends ZoneCommand

case class CreateMember(zoneId: ZoneId, member: Member) extends ZoneCommand

case class UpdateMember(zoneId: ZoneId, memberId: MemberId, member: Member) extends ZoneCommand

case class DeleteMember(zoneId: ZoneId, memberId: MemberId) extends ZoneCommand

case class CreateAccount(zoneId: ZoneId, account: Account) extends ZoneCommand

case class UpdateAccount(zoneId: ZoneId, accountId: AccountId, account: Account) extends ZoneCommand

case class DeleteAccount(zoneId: ZoneId, accountId: AccountId) extends ZoneCommand

case class AddTransaction(zoneId: ZoneId, transaction: Transaction) extends ZoneCommand

object Command {

  val CommandTypeFormats = MethodFormats(
    "createZone" -> Json.format[CreateZone],
    "joinZone" -> Json.format[JoinZone],
    "quitZone" -> Json.format[QuitZone],
    "setZoneName" -> Json.format[SetZoneName],
    "createMember" -> Json.format[CreateMember],
    "updateMember" -> Json.format[UpdateMember],
    "deleteMember" -> Json.format[DeleteMember],
    "createAccount" -> Json.format[CreateAccount],
    "updateAccount" -> Json.format[UpdateAccount],
    "deleteAccount" -> Json.format[DeleteAccount],
    "addTransaction" -> Json.format[AddTransaction]
  )

  def read(jsonRpcRequestMessage: JsonRpcRequestMessage): Option[JsResult[Command]] =
    CommandTypeFormats.find(_.methodName == jsonRpcRequestMessage.method).map(
      typeChoiceMapping => jsonRpcRequestMessage.params.fold(
        _ => JsError("command parameters must be named"),
        jsObject => typeChoiceMapping.fromJson(jsObject)
      )
    ).map(_.fold(

      /*
       * We do this in order to drop any non-root path that may have existed in the success case.
       */
      invalid => JsError(invalid),
      valid => JsSuccess(valid)
    ))

  def write(command: Command, id: Either[String, Int]): JsonRpcRequestMessage = {
    val mapping = CommandTypeFormats.find(_.matchesInstance(command))
      .getOrElse(sys.error(s"No format found for ${command.getClass}"))
    JsonRpcRequestMessage(mapping.methodName, Right(mapping.toJson(command).asInstanceOf[JsObject]), id)
  }

}

sealed trait CommandResponse extends Message

case class CommandErrorResponse(code: Int, message: String, data: Option[JsValue]) extends CommandResponse

sealed trait CommandResultResponse extends CommandResponse

case class ZoneCreated(zoneId: ZoneId) extends CommandResultResponse

case class ZoneJoined(zone: Zone, connectedClients: Set[PublicKey]) extends CommandResultResponse

case object ZoneQuit extends CommandResultResponse

case object ZoneNameSet extends CommandResultResponse

case class MemberCreated(memberId: MemberId) extends CommandResultResponse

case object MemberUpdated extends CommandResultResponse

case object MemberDeleted extends CommandResultResponse

case class AccountCreated(accountId: AccountId) extends CommandResultResponse

case object AccountUpdated extends CommandResultResponse

case object AccountDeleted extends CommandResultResponse

case class TransactionAdded(transactionId: TransactionId) extends CommandResultResponse

object CommandResponse {

  val CommandResponseFormats = MethodFormats(
    "createZone" -> Json.format[ZoneCreated],
    "joinZone" -> Json.format[ZoneJoined],
    "quitZone" -> ZoneQuit,
    "setZoneName" -> ZoneNameSet,
    "createMember" -> Json.format[MemberCreated],
    "updateMember" -> MemberUpdated,
    "deleteMember" -> MemberDeleted,
    "createAccount" -> Json.format[AccountCreated],
    "updateAccount" -> AccountUpdated,
    "deleteAccount" -> AccountDeleted,
    "addTransaction" -> Json.format[TransactionAdded]
  )

  def read(jsonRpcResponseMessage: JsonRpcResponseMessage, method: String): JsResult[CommandResponse] =
    jsonRpcResponseMessage.eitherErrorOrResult.fold(
      error => JsSuccess(CommandErrorResponse(error.code, error.message, error.data)),
      result => CommandResponseFormats.find(_.methodName == method).get.fromJson(result)
    ).fold(

        /*
         * We do this in order to drop any non-root path that may have existed in the success case.
         */
        invalid => JsError(invalid),
        valid => JsSuccess(valid)
      )

  def write(commandResponse: CommandResponse, id: Either[String, Int]): JsonRpcResponseMessage = {
    val eitherErrorOrResult = commandResponse match {
      case CommandErrorResponse(code, message, data) => Left(
        JsonRpcResponseError(code, message, data)
      )
      case commandResultResponse: CommandResultResponse =>
        val mapping = CommandResponseFormats.find(_.matchesInstance(commandResponse))
          .getOrElse(sys.error(s"No format found for ${commandResponse.getClass}"))
        Right(mapping.toJson(commandResponse))
    }
    JsonRpcResponseMessage(eitherErrorOrResult, Some(id))
  }

}

sealed trait Notification extends Message

sealed abstract class NotificationMethodName(val name: String)

sealed trait ZoneNotification extends Notification {

  val zoneId: ZoneId

}

case class ZoneState(zoneId: ZoneId, zone: Zone) extends ZoneNotification

case class ZoneTerminated(zoneId: ZoneId) extends ZoneNotification

case class ClientJoinedZone(zoneId: ZoneId, publicKey: PublicKey) extends ZoneNotification

case class ClientQuitZone(zoneId: ZoneId, publicKey: PublicKey) extends ZoneNotification

object Notification {

  val NotificationFormats = MethodFormats(
    "zoneState" -> Json.format[ZoneState],
    "zoneTerminated" -> Json.format[ZoneTerminated],
    "clientJoinedZone" -> Json.format[ClientJoinedZone],
    "clientQuitZone" -> Json.format[ClientQuitZone]
  )

  def read(jsonRpcNotificationMessage: JsonRpcNotificationMessage): Option[JsResult[Notification]] =
    NotificationFormats.find(_.methodName == jsonRpcNotificationMessage.method).map(
      typeChoiceMapping => jsonRpcNotificationMessage.params.fold(
        _ => JsError("notification parameters must be named"),
        jsObject => typeChoiceMapping.fromJson(jsObject)
      )
    ).map(_.fold(

      /*
       * We do this in order to drop any non-root path that may have existed in the success case.
       */
      invalid => JsError(invalid),
      valid => JsSuccess(valid)
    ))

  def write(notification: Notification): JsonRpcNotificationMessage = {
    val mapping = NotificationFormats.find(_.matchesInstance(notification))
      .getOrElse(sys.error(s"No format found for ${notification.getClass}"))
    JsonRpcNotificationMessage(mapping.methodName, Right(mapping.toJson(notification).asInstanceOf[JsObject]))
  }

}