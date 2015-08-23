package actors

import java.util.UUID

import actors.ClientConnection._
import actors.ZoneValidator._
import akka.actor._
import com.dhpcs.jsonrpc._
import com.dhpcs.liquidity.models._
import play.api.libs.json._

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object ClientConnection {

  private val receiveTimeout = 30.seconds

  def props(publicKey: PublicKey, zoneValidatorShardRegion: ActorRef)(upstream: ActorRef) =
    Props(new ClientConnection(publicKey, zoneValidatorShardRegion, upstream))

  private def readCommand(jsonString: String):
  (Either[(JsonRpcResponseError, Option[Either[String, Int]]), (Command, Either[String, Int])]) =

    Try(Json.parse(jsonString)) match {

      case Failure(e) =>

        Left(
          JsonRpcResponseError.parseError(e),
          None
        )

      case Success(jsValue) =>

        Json.fromJson[JsonRpcRequestMessage](jsValue).fold(

          errors => Left(
            JsonRpcResponseError.invalidRequest(errors),
            None
          ),

          jsonRpcRequestMessage =>

            Command.read(jsonRpcRequestMessage)
              .fold[(Either[(JsonRpcResponseError, Option[Either[String, Int]]), (Command, Either[String, Int])])](

                Left(
                  JsonRpcResponseError.methodNotFound(jsonRpcRequestMessage.method),
                  Some(jsonRpcRequestMessage.id)
                )

              )(commandJsResult => commandJsResult.fold(

              errors => Left(
                JsonRpcResponseError.invalidParams(errors),
                Some(jsonRpcRequestMessage.id)
              ),

              command => Right(
                command,
                jsonRpcRequestMessage.id
              )

            ))

        )

    }

}

class ClientConnection(publicKey: PublicKey,
                       zoneValidatorShardRegion: ActorRef,
                       upstream: ActorRef) extends Actor with ActorLogging {

  context.setReceiveTimeout(receiveTimeout)

  override def postStop() {
    log.debug(s"Stopped actor for ${publicKey.fingerprint}")
  }

  override def preStart() {
    log.debug(s"Started actor for ${publicKey.fingerprint}")
  }

  override def receive = {

    case ReceiveTimeout =>

      val keepAliveNotification = KeepAliveNotification

      log.debug(s"Sending $keepAliveNotification")

      upstream ! Json.stringify(
        Json.toJson(
          Notification.write(keepAliveNotification)
        )
      )

    case json: String =>

      readCommand(json) match {

        case Left((jsonRpcResponseError, id)) =>

          log.debug(s"Receive error $jsonRpcResponseError")

          sender ! Json.stringify(
            Json.toJson(
              JsonRpcResponseMessage(Left(jsonRpcResponseError), id)
            )
          )

        case Right((command, id)) =>

          log.debug(s"Received $command")

          command match {

            case createZoneCommand: CreateZoneCommand =>

              val zoneId = ZoneId.generate

              zoneValidatorShardRegion ! EnvelopedMessage(
                zoneId,
                AuthenticatedCommandWithId(publicKey, createZoneCommand, id)
              )

            case zoneCommand: ZoneCommand =>

              zoneCommand match {

                case joinZoneCommand@JoinZoneCommand(zoneId) =>

                  zoneValidatorShardRegion ! EnvelopedMessage(
                    zoneId,
                    Identify(AuthenticatedCommandWithId(publicKey, joinZoneCommand, id))
                  )

                case quitZoneCommand@QuitZoneCommand(zoneId) =>

                  zoneValidatorShardRegion ! EnvelopedMessage(
                    zoneId,
                    Identify(AuthenticatedCommandWithId(publicKey, quitZoneCommand, id))
                  )

                case _ =>

                  zoneValidatorShardRegion ! AuthenticatedCommandWithId(publicKey, zoneCommand, id)

              }

          }

      }

    case actorIdentity@
      ActorIdentity(authenticatedCommandWithId@AuthenticatedCommandWithId(_, _: JoinZoneCommand, _), Some(validator)) =>

      log.debug(s"Received $actorIdentity")

      context.watch(validator)

      zoneValidatorShardRegion ! authenticatedCommandWithId

    case actorIdentity@
      ActorIdentity(authenticatedCommandWithId@AuthenticatedCommandWithId(_, _: QuitZoneCommand, _), Some(validator)) =>

      log.debug(s"Received $actorIdentity")

      zoneValidatorShardRegion ! authenticatedCommandWithId

      context.unwatch(validator)

    case ZoneAlreadyExists(createZoneCommand) =>

      self ! createZoneCommand

    case ResponseWithId(response, id) =>

      log.debug(s"Received $response")

      upstream ! Json.stringify(
        Json.toJson(
          Response.write(response, id)
        )
      )

    case notification: Notification =>

      log.debug(s"Received $notification")

      upstream ! Json.stringify(
        Json.toJson(
          Notification.write(notification)
        )
      )

    case Terminated(validator) =>

      val zoneId = ZoneId(UUID.fromString(validator.path.name))

      val zoneTerminatedNotification = ZoneTerminatedNotification(zoneId)

      log.debug(s"Sending $zoneTerminatedNotification")

      upstream ! Json.stringify(
        Json.toJson(
          Notification.write(zoneTerminatedNotification)
        )
      )

  }

}
