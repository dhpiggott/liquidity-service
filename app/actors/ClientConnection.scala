package actors

import actors.ClientConnection._
import actors.ZoneRegistry.{CreateValidator, GetValidator, ValidatorCreated, ValidatorGot}
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

  case class AuthenticatedCommand(publicKey: PublicKey, command: Command, id: Either[String, Int])

  private case class CacheValidator(zoneId: ZoneId, validator: ActorRef)

}

class ClientConnection(publicKey: PublicKey,
                       zoneRegistry: ActorRef,
                       upstream: ActorRef) extends Actor with ActorLogging {

  import context.dispatcher

  var joinedValidators = Map.empty[ZoneId, ActorRef]

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

  def receive = {

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

            case command: CreateZone =>

              log.debug(s"Received $command}")

              (zoneRegistry ? CreateValidator)
                .mapTo[ValidatorCreated]
                .map { case ValidatorCreated(zoneId, validator) =>
                validator ! AuthenticatedCommand(publicKey, command, id)
                CacheValidator(zoneId, validator)
              }.pipeTo(self)

            case command@JoinZone(zoneId) =>

              log.debug(s"Received $command}")

              (zoneRegistry ? GetValidator(zoneId))
                .mapTo[ValidatorGot]
                .map { case ValidatorGot(validator) =>
                validator ! AuthenticatedCommand(publicKey, command, id)
                CacheValidator(zoneId, validator)
              }.pipeTo(self)

            case command@QuitZone(zoneId) =>

              log.debug(s"Received $command}")

              joinedValidators.get(zoneId).foreach { validator =>
                validator ! AuthenticatedCommand(publicKey, command, id)
                context.unwatch(validator)

                joinedValidators -= zoneId
              }

            case zoneCommand: ZoneCommand =>

              log.debug(s"Received $zoneCommand}")

              joinedValidators.get(zoneCommand.zoneId).foreach(
                _ ! AuthenticatedCommand(publicKey, zoneCommand, id)
              )

          }

      }

    case cacheValidator@CacheValidator(zoneId, validator) =>

      log.debug(s"Received $cacheValidator}")

      context.watch(validator)

      joinedValidators += (zoneId -> validator)

    case (commandResponse: CommandResponse, id: Either[String, Int]@unchecked) =>

      log.debug(s"Received $commandResponse}")

      upstream ! Json.stringify(
        Json.toJson(
          CommandResponse.write(commandResponse, id)
        )
      )

    case notification: Notification =>

      log.debug(s"Received $notification}")

      upstream ! Json.stringify(
        Json.toJson(
          Notification.write(notification)
        )
      )

    case terminated@Terminated(validator) =>

      log.debug(s"Received $terminated}")

      joinedValidators = joinedValidators.filterNot { case (zoneId, v) =>
        val remove = v == validator
        if (remove) {
          upstream ! Notification.write(ZoneTerminated(zoneId))
        }
        remove
      }

  }

}