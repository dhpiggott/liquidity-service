package actors

import actors.ClientConnection._
import actors.ZoneValidator._
import akka.actor._
import akka.contrib.pattern.DistributedPubSubExtension
import akka.contrib.pattern.DistributedPubSubMediator.{Subscribe, SubscribeAck, Unsubscribe, UnsubscribeAck}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import com.dhpcs.jsonrpc._
import com.dhpcs.liquidity.models._
import play.api.libs.json._

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object ClientConnection {

  implicit val GetValidatorTimeout = Timeout(1.second)

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

  import context.dispatcher

  context.setReceiveTimeout(30.seconds)

  private val mediator = DistributedPubSubExtension(context.system).mediator

  override def postStop() {
    log.debug(s"Stopped actor for ${publicKey.fingerprint}")
  }

  override def preStart() {
    log.debug(s"Started actor for ${publicKey.fingerprint}")
  }

  override def receive = {

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

          command match {

            case createZoneCommand: CreateZoneCommand =>

              log.debug(s"Received $createZoneCommand")

              val zoneId = ZoneId.generate

              (zoneValidatorShardRegion ? EnvelopedAuthenticatedCommandWithId(
                zoneId,
                AuthenticatedCommandWithId(publicKey, createZoneCommand, id)
              )).map {
                case ZoneAlreadyExists => createZoneCommand
                case response => response
              }.pipeTo(self)

            case zoneCommand: ZoneCommand =>

              log.debug(s"Received $zoneCommand")

              zoneCommand match {
                case JoinZoneCommand(zoneId) => mediator ! Subscribe(zoneId.toString, self)
                case QuitZoneCommand(zoneId) => mediator ! Unsubscribe(zoneId.toString, self)
                case _ =>
              }

              zoneValidatorShardRegion ! AuthenticatedCommandWithId(publicKey, zoneCommand, id)

          }

      }

    case subscribeAck@SubscribeAck(Subscribe(zoneIdString, None, `self`)) =>

      log.debug(s"Received $subscribeAck")

    case unsubscribeAck@UnsubscribeAck(Unsubscribe(zoneIdString, None, `self`)) =>

      log.debug(s"Received $unsubscribeAck")

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

    case ReceiveTimeout =>

      val keepAliveNotification = KeepAliveNotification

      log.debug(s"Sending $keepAliveNotification")

      upstream ! Json.stringify(
        Json.toJson(
          Notification.write(keepAliveNotification)
        )
      )

    case ZoneStarting(zoneId) =>

      mediator ! Unsubscribe(zoneId.toString, self)

      val zoneTerminatedNotification = ZoneTerminatedNotification(zoneId)

      log.debug(s"Sending $zoneTerminatedNotification")

      upstream ! Json.stringify(
        Json.toJson(
          Notification.write(zoneTerminatedNotification)
        )
      )

    case ZoneStopped(zoneId) =>

      mediator ! Unsubscribe(zoneId.toString, self)

      val zoneTerminatedNotification = ZoneTerminatedNotification(zoneId)

      log.debug(s"Sending $zoneTerminatedNotification")

      upstream ! Json.stringify(
        Json.toJson(
          Notification.write(zoneTerminatedNotification)
        )
      )

  }

}
