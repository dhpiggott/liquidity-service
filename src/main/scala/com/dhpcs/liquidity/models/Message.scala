package com.dhpcs.liquidity.models

import com.dhpcs.jsonrpc._
import com.dhpcs.liquidity.models.Message.MethodFormats
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
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

case class CreateZoneCommand(name: Option[String],
                             equityOwnerName: Option[String],
                             equityOwnerPublicKey: PublicKey,
                             equityOwnerMetadata: Option[JsObject],
                             equityAccountName: Option[String],
                             equityAccountMetadata: Option[JsObject],
                             metadata: Option[JsObject] = None) extends Command

case class JoinZoneCommand(zoneId: ZoneId) extends ZoneCommand

case class QuitZoneCommand(zoneId: ZoneId) extends ZoneCommand

case class ChangeZoneNameCommand(zoneId: ZoneId,
                                 name: Option[String]) extends ZoneCommand

case class CreateMemberCommand(zoneId: ZoneId,
                               name: Option[String],
                               ownerPublicKey: PublicKey,
                               metadata: Option[JsObject] = None) extends ZoneCommand

case class UpdateMemberCommand(zoneId: ZoneId,
                               member: Member) extends ZoneCommand

case class CreateAccountCommand(zoneId: ZoneId,
                                name: Option[String],
                                ownerMemberIds: Set[MemberId],
                                metadata: Option[JsObject] = None) extends ZoneCommand

case class UpdateAccountCommand(zoneId: ZoneId,
                                account: Account) extends ZoneCommand

case class AddTransactionCommand(zoneId: ZoneId,
                                 actingAs: MemberId,
                                 description: Option[String],
                                 from: AccountId,
                                 to: AccountId,
                                 value: BigDecimal,
                                 metadata: Option[JsObject] = None) extends ZoneCommand {
  require(value >= 0)
}

object AddTransactionCommand {

  implicit val AddTransactionCommandFormat: Format[AddTransactionCommand] = (
    (JsPath \ "zoneId").format[ZoneId] and
      (JsPath \ "actingAs").format[MemberId] and
      (JsPath \ "description").formatNullable[String] and
      (JsPath \ "from").format[AccountId] and
      (JsPath \ "to").format[AccountId] and
      (JsPath \ "value").format(min[BigDecimal](0)) and
      (JsPath \ "metadata").formatNullable[JsObject]
    )((zoneId, actingAs, description, from, to, value, metadata) =>
    AddTransactionCommand(
      zoneId,
      actingAs,
      description,
      from,
      to,
      value,
      metadata
    ), addTransactionCommand =>
    (addTransactionCommand.zoneId,
      addTransactionCommand.actingAs,
      addTransactionCommand.description,
      addTransactionCommand.from,
      addTransactionCommand.to,
      addTransactionCommand.value,
      addTransactionCommand.metadata)
    )

}

object Command {

  val CommandTypeFormats = MethodFormats(
    "createZone" -> Json.format[CreateZoneCommand],
    "joinZone" -> Json.format[JoinZoneCommand],
    "quitZone" -> Json.format[QuitZoneCommand],
    "changeZoneName" -> Json.format[ChangeZoneNameCommand],
    "createMember" -> Json.format[CreateMemberCommand],
    "updateMember" -> Json.format[UpdateMemberCommand],
    "createAccount" -> Json.format[CreateAccountCommand],
    "updateAccount" -> Json.format[UpdateAccountCommand],
    "addTransaction" -> AddTransactionCommand.AddTransactionCommandFormat
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

sealed trait Response extends Message

case class ErrorResponse(code: Int, message: String, data: Option[JsValue] = None) extends Response

sealed trait ResultResponse extends Response

case class CreateZoneResponse(zone: Zone) extends ResultResponse

case class JoinZoneResponse(zone: Zone,
                            connectedClients: Set[PublicKey]) extends ResultResponse

case object QuitZoneResponse extends ResultResponse

case object ChangeZoneNameResponse extends ResultResponse

case class CreateMemberResponse(member: Member) extends ResultResponse

case object UpdateMemberResponse extends ResultResponse

case class CreateAccountResponse(account: Account) extends ResultResponse

case object UpdateAccountResponse extends ResultResponse

case class AddTransactionResponse(transaction: Transaction) extends ResultResponse

object Response {

  val ResponseFormats = MethodFormats(
    "createZone" -> Json.format[CreateZoneResponse],
    "joinZone" -> Json.format[JoinZoneResponse],
    "quitZone" -> QuitZoneResponse,
    "changeZoneName" -> ChangeZoneNameResponse,
    "createMember" -> Json.format[CreateMemberResponse],
    "updateMember" -> UpdateMemberResponse,
    "createAccount" -> Json.format[CreateAccountResponse],
    "updateAccount" -> UpdateAccountResponse,
    "addTransaction" -> Json.format[AddTransactionResponse]
  )

  def read(jsonRpcResponseMessage: JsonRpcResponseMessage, method: String): JsResult[Response] =
    jsonRpcResponseMessage.eitherErrorOrResult.fold(
      error => JsSuccess(ErrorResponse(error.code, error.message, error.data)),
      result => ResponseFormats.find(_.methodName == method).get.fromJson(result)
    ).fold(

        /*
         * We do this in order to drop any non-root path that may have existed in the success case.
         */
        invalid => JsError(invalid),
        valid => JsSuccess(valid)
      )

  def write(response: Response, id: Either[String, Int]): JsonRpcResponseMessage = {
    val eitherErrorOrResult = response match {
      case ErrorResponse(code, message, data) => Left(
        JsonRpcResponseError.applicationError(code, message, data)
      )
      case resultResponse: ResultResponse =>
        val mapping = ResponseFormats.find(_.matchesInstance(resultResponse))
          .getOrElse(sys.error(s"No format found for ${response.getClass}"))
        Right(mapping.toJson(response))
    }
    JsonRpcResponseMessage(eitherErrorOrResult, Some(id))
  }

}

sealed trait Notification extends Message

sealed abstract class NotificationMethodName(val name: String)

sealed trait ZoneNotification extends Notification {

  val zoneId: ZoneId

}

case object KeepAliveNotification extends Notification

case class ClientJoinedZoneNotification(zoneId: ZoneId,
                                        publicKey: PublicKey) extends ZoneNotification

case class ClientQuitZoneNotification(zoneId: ZoneId,
                                      publicKey: PublicKey) extends ZoneNotification

case class ZoneTerminatedNotification(zoneId: ZoneId) extends ZoneNotification

case class ZoneNameChangedNotification(zoneId: ZoneId,
                                       name: Option[String]) extends ZoneNotification

case class MemberCreatedNotification(zoneId: ZoneId,
                                     member: Member) extends ZoneNotification

case class MemberUpdatedNotification(zoneId: ZoneId,
                                     member: Member) extends ZoneNotification

case class AccountCreatedNotification(zoneId: ZoneId,
                                      account: Account) extends ZoneNotification

case class AccountUpdatedNotification(zoneId: ZoneId,
                                      account: Account) extends ZoneNotification

case class TransactionAddedNotification(zoneId: ZoneId,
                                        transaction: Transaction) extends ZoneNotification

object Notification {

  val NotificationFormats = MethodFormats(
    "keepAlive" -> KeepAliveNotification,
    "clientJoinedZone" -> Json.format[ClientJoinedZoneNotification],
    "clientQuitZone" -> Json.format[ClientQuitZoneNotification],
    "zoneTerminated" -> Json.format[ZoneTerminatedNotification],
    "zoneNameChanged" -> Json.format[ZoneNameChangedNotification],
    "memberCreated" -> Json.format[MemberCreatedNotification],
    "memberUpdated" -> Json.format[MemberUpdatedNotification],
    "accountCreated" -> Json.format[AccountCreatedNotification],
    "accountUpdated" -> Json.format[AccountUpdatedNotification],
    "transactionAdded" -> Json.format[TransactionAddedNotification]
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