package actors

import actors.ClientConnection._
import actors.ZoneRegistry.{CreateValidator, GetValidator, ValidatorCreated, ValidatorGot}
import actors.ZoneValidator.{AuthenticatedCommandWithId, ResponseWithId}
import akka.actor._
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import com.dhpcs.jsonrpc._
import com.dhpcs.liquidity.models._
import play.api.libs.json._

import scala.util.{Failure, Success, Try}

object ClientConnection {

  implicit val GetValidatorTimeout = Timeout(ZoneRegistry.StoppingChildRetryDelay * 10)

  def props(publicKey: PublicKey, zoneRegistry: ActorRef)(upstream: ActorRef) =
    Props(new ClientConnection(publicKey, zoneRegistry, upstream))

  private case class CacheValidator(zoneId: ZoneId, validator: ActorRef)

}

// TODO: Receive timeout and/or pings?
class ClientConnection(publicKey: PublicKey,
                       zoneRegistry: ActorRef,
                       upstream: ActorRef) extends Actor with ActorLogging {

  import context.dispatcher

  override def postStop() {
    log.debug(s"Stopped actor for ${publicKey.fingerprint}")
  }

  override def preStart() {
    log.debug(s"Started actor for ${publicKey.fingerprint}")
  }

  def readCommand(jsonString: String):
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

  def receive = receive(Map.empty[ZoneId, ActorRef])

  def receive(joinedValidators: Map[ZoneId, ActorRef]): Receive = {

    case jsonString: String =>

      readCommand(jsonString) match {

        case Left((jsonRpcResponseError, maybeId)) =>

          log.debug(s"Receive error $jsonRpcResponseError}")

          sender ! Json.stringify(
            Json.toJson(
              JsonRpcResponseMessage(Left(jsonRpcResponseError), maybeId)
            )
          )

        case Right((command, id)) =>

          command match {

            case command: CreateZoneCommand =>

              log.debug(s"Received $command}")

              (zoneRegistry ? CreateValidator)
                .mapTo[ValidatorCreated]
                .foreach { case ValidatorCreated(zoneId, validator) =>
                validator ! AuthenticatedCommandWithId(publicKey, command, id)
              }

            case command@JoinZoneCommand(zoneId) =>

              log.debug(s"Received $command}")

              (zoneRegistry ? GetValidator(zoneId))
                .mapTo[ValidatorGot]
                .map { case ValidatorGot(validator) =>
                validator ! AuthenticatedCommandWithId(publicKey, command, id)
                CacheValidator(zoneId, validator)
              }.pipeTo(self)

            case command@QuitZoneCommand(zoneId) =>

              log.debug(s"Received $command}")

              joinedValidators.get(zoneId).foreach { validator =>
                validator ! AuthenticatedCommandWithId(publicKey, command, id)
                context.unwatch(validator)

                val newJoinedValidators = joinedValidators - zoneId

                context.become(receive(newJoinedValidators))
              }

            case zoneCommand: ZoneCommand =>

              log.debug(s"Received $zoneCommand}")

              joinedValidators.get(zoneCommand.zoneId).foreach(
                _ ! AuthenticatedCommandWithId(publicKey, zoneCommand, id)
              )

          }

      }

    case ResponseWithId(response, id) =>

      log.debug(s"Received $response}")

      upstream ! Json.stringify(
        Json.toJson(
          Response.write(response, id)
        )
      )

    case notification: Notification =>

      log.debug(s"Received $notification}")

      upstream ! Json.stringify(
        Json.toJson(
          Notification.write(notification)
        )
      )

    case cacheValidator@CacheValidator(zoneId, validator) =>

      log.debug(s"Received $cacheValidator}")

      context.watch(validator)

      val newJoinedValidators = joinedValidators + (zoneId -> validator)

      context.become(receive(newJoinedValidators))

    case terminated@Terminated(validator) =>

      log.debug(s"Received $terminated}")

      val newJoinedValidators = joinedValidators.filterNot { case (zoneId, v) =>
        val remove = v == validator
        if (remove) {
          upstream ! Notification.write(ZoneTerminatedNotification(zoneId))
        }
        remove
      }

      context.become(receive(newJoinedValidators))

  }

}
