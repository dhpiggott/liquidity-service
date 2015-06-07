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

  def receive = {

    case jsonString: String =>

      Try(Json.parse(jsonString)) match {

        case Failure(e) =>

          sender ! Json.toJson(
            // TODO: Extract all error constants to liquidity-common - see http://www.jsonrpc.org/specification#error_object
            JsonRpcResponseError(
              -32700,
              "Parse error",
              Some(
                JsObject(
                  Seq(
                    "meaning" -> JsString("Invalid JSON was received by the server.\nAn error occurred on the server while parsing the JSON text."),
                    "error" -> JsString(e.getMessage)
                  )
                )
              )
            )
          )

        case Success(jsValue) =>

          val jsonRpcRequestMessageJsResult = Json.fromJson[JsonRpcRequestMessage](jsValue)

          jsonRpcRequestMessageJsResult.fold(

            invalid => sender ! Json.toJson(
              // TODO: Extract all error constants to liquidity-common - see http://www.jsonrpc.org/specification#error_object
              JsonRpcResponseError(
                -32600,
                "Invalid Request",
                Some(
                  JsObject(
                    Seq(
                      "meaning" -> JsString("The JSON sent is not a valid Request object."),
                      "error" -> JsError.toJson(invalid)
                    )
                  )
                )
              )
            ),

            valid =>

              // TODO: Make readCommand etc. return e.g. JsResult based on whether the method is valid etc. and fold on that
              Command.readCommand(valid) match {

                case command: CreateZone =>

                  log.debug(s"Received $command}")

                  (zoneRegistry ? CreateValidator)
                    .mapTo[ValidatorCreated]
                    .map { case ValidatorCreated(zoneId, validator) =>
                    validator ! AuthenticatedCommand(publicKey, command, valid.id)
                    CacheValidator(zoneId, validator)
                  }.pipeTo(self)

                case command@JoinZone(zoneId) =>

                  log.debug(s"Received $command}")

                  (zoneRegistry ? GetValidator(zoneId))
                    .mapTo[ValidatorGot]
                    .map { case ValidatorGot(validator) =>
                    validator ! AuthenticatedCommand(publicKey, command, valid.id)
                    CacheValidator(zoneId, validator)
                  }.pipeTo(self)

                case command@QuitZone(zoneId) =>

                  log.debug(s"Received $command}")

                  joinedValidators.get(zoneId).foreach { validator =>
                    validator ! AuthenticatedCommand(publicKey, command, valid.id)
                    context.unwatch(validator)

                    joinedValidators -= zoneId
                  }

                case zoneCommand: ZoneCommand =>

                  log.debug(s"Received $zoneCommand}")

                  joinedValidators.get(zoneCommand.zoneId).foreach(
                    _ ! AuthenticatedCommand(publicKey, zoneCommand, valid.id)
                  )

              }

          )
      }

    case cacheValidator@CacheValidator(zoneId, validator) =>

      log.debug(s"Received $cacheValidator}")

      context.watch(validator)

      joinedValidators += (zoneId -> validator)

    case (commandResponse: CommandResponse, id: Either[String, Int]@unchecked) =>

      log.debug(s"Received $commandResponse}")

      upstream ! Json.toJson(CommandResponse.writeCommandResponse(commandResponse, id))

    case notification: Notification =>

      log.debug(s"Received $notification}")

      upstream ! Json.toJson(Notification.writeNotification(notification))

    case terminated@Terminated(validator) =>

      log.debug(s"Received $terminated}")

      joinedValidators = joinedValidators.filterNot { case (zoneId, v) =>
        val remove = v == validator
        if (remove) {
          upstream ! Notification.writeNotification(ZoneTerminated(zoneId))
        }
        remove
      }

  }

}