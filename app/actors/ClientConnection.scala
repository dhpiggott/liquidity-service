package actors

import java.util.UUID

import actors.ClientConnection._
import actors.ZoneValidator._
import akka.actor._
import akka.persistence.{AtLeastOnceDelivery, PersistentActor}
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

  case class MessageReceivedConfirmation(deliveryId: Long)

  private class KeepAliveGenerator extends Actor {

    import actors.ClientConnection.KeepAliveGenerator._

    context.setReceiveTimeout(receiveTimeout)

    override def receive: Receive = {

      case ReceiveTimeout =>

        context.parent ! SendKeepAlive

      case FrameReceivedEvent | FrameSentEvent =>

    }

  }

  private object KeepAliveGenerator {

    case object FrameReceivedEvent

    case object FrameSentEvent

    case object SendKeepAlive

  }

}

class ClientConnection(publicKey: PublicKey,
                       zoneValidatorShardRegion: ActorRef,
                       upstream: ActorRef) extends PersistentActor with ActorLogging with AtLeastOnceDelivery {

  import actors.ClientConnection.KeepAliveGenerator._

  private val keepAliveActor = context.actorOf(Props[KeepAliveGenerator])

  private var nextExpectedMessageSequenceNumbers = Map.empty[ActorRef, Long].withDefaultValue(0L)
  private var commandSequenceNumbers = Map.empty[ZoneId, Long].withDefaultValue(0L)
  private var pendingDeliveries = Map.empty[ZoneId, Set[Long]].withDefaultValue(Set.empty)

  private def createZone(createZoneCommand: CreateZoneCommand, correlationId: Either[String, Int]) {
    val zoneId = ZoneId.generate
    val sequenceNumber = commandSequenceNumbers(zoneId)
    commandSequenceNumbers = commandSequenceNumbers + (zoneId -> (sequenceNumber + 1))
    deliver(zoneValidatorShardRegion.path, { deliveryId =>
      pendingDeliveries = pendingDeliveries + (zoneId -> (pendingDeliveries(zoneId) + deliveryId))
      EnvelopedMessage(
        zoneId,
        AuthenticatedCommandWithIds(
          publicKey,
          createZoneCommand,
          correlationId,
          sequenceNumber,
          deliveryId
        )
      )
    })
  }

  override def persistenceId: String = s"${publicKey.productPrefix}(${publicKey.fingerprint})"

  override def postStop() {
    log.info(s"Stopped actor for ${publicKey.fingerprint}")
    super.postStop()
  }

  override def preStart() {
    log.info(s"Started actor for ${publicKey.fingerprint}")
    super.preStart()
  }

  override def receiveCommand = {

    case SendKeepAlive =>

      upstream ! Json.stringify(
        Json.toJson(
          Notification.write(KeepAliveNotification)
        )
      )

    case json: String =>

      keepAliveActor ! FrameReceivedEvent

      readCommand(json) match {

        case Left((jsonRpcResponseError, id)) =>

          log.warning(s"Receive error: $jsonRpcResponseError")

          sender ! Json.stringify(
            Json.toJson(
              JsonRpcResponseMessage(Left(jsonRpcResponseError), id)
            )
          )

        case Right((command, correlationId)) =>

          command match {

            case createZoneCommand: CreateZoneCommand =>

              createZone(createZoneCommand, correlationId)

            case zoneCommand: ZoneCommand =>

              val zoneId = zoneCommand.zoneId

              val sequenceNumber = commandSequenceNumbers(zoneId)
              commandSequenceNumbers = commandSequenceNumbers + (zoneId -> (sequenceNumber + 1))
              deliver(zoneValidatorShardRegion.path, { deliveryId =>
                pendingDeliveries = pendingDeliveries + (zoneId -> (pendingDeliveries(zoneId) + deliveryId))
                AuthenticatedCommandWithIds(
                  publicKey,
                  zoneCommand,
                  correlationId,
                  sequenceNumber,
                  deliveryId
                )
              })

          }

      }

    case ZoneAlreadyExists(createZoneCommand, correlationId, sequenceNumber, deliveryId) =>

      val nextExpectedMessageSequenceNumber = nextExpectedMessageSequenceNumbers(sender())

      if (sequenceNumber <= nextExpectedMessageSequenceNumber) {

        sender ! MessageReceivedConfirmation(deliveryId)

      }

      if (sequenceNumber == nextExpectedMessageSequenceNumber) {

        nextExpectedMessageSequenceNumbers = nextExpectedMessageSequenceNumbers + (sender() -> (sequenceNumber + 1))

        createZone(createZoneCommand, correlationId)

      }

    case ZoneRestarted(zoneId, sequenceNumber, deliveryId) =>

      val nextExpectedMessageSequenceNumber = nextExpectedMessageSequenceNumbers(sender())

      if (sequenceNumber <= nextExpectedMessageSequenceNumber) {

        sender ! MessageReceivedConfirmation(deliveryId)

      }

      if (sequenceNumber == nextExpectedMessageSequenceNumber) {

        /*
         * Remove previous validator entry, add new validator entry.
         */
        nextExpectedMessageSequenceNumbers = nextExpectedMessageSequenceNumbers.filterKeys { validator =>
          ZoneId(UUID.fromString(validator.path.name)) != zoneId
        } + (sender() -> (sequenceNumber + 1))

        commandSequenceNumbers = commandSequenceNumbers - zoneId

        pendingDeliveries(zoneId).foreach(confirmDelivery)
        pendingDeliveries = pendingDeliveries - zoneId

        upstream ! Json.stringify(
          Json.toJson(
            Notification.write(ZoneTerminatedNotification(zoneId))
          )
        )

        keepAliveActor ! FrameSentEvent

      }

    case CommandReceivedConfirmation(zoneId, deliveryId) =>

      confirmDelivery(deliveryId)

      pendingDeliveries = pendingDeliveries + (zoneId -> (pendingDeliveries(zoneId) - deliveryId))

      if (pendingDeliveries(zoneId).isEmpty) {

        pendingDeliveries = pendingDeliveries - zoneId

      }

    case ResponseWithIds(response, correlationId, sequenceNumber, deliveryId) =>

      val nextExpectedMessageSequenceNumber = nextExpectedMessageSequenceNumbers(sender())

      if (sequenceNumber <= nextExpectedMessageSequenceNumber) {

        sender ! MessageReceivedConfirmation(deliveryId)

      }

      if (sequenceNumber == nextExpectedMessageSequenceNumber) {

        nextExpectedMessageSequenceNumbers = nextExpectedMessageSequenceNumbers + (sender() -> (sequenceNumber + 1))

        upstream ! Json.stringify(
          Json.toJson(
            Response.write(response, correlationId)
          )
        )

        keepAliveActor ! FrameSentEvent

      }

    case NotificationWithIds(notification, sequenceNumber, deliveryId) =>

      val nextExpectedMessageSequenceNumber = nextExpectedMessageSequenceNumbers(sender())

      if (sequenceNumber <= nextExpectedMessageSequenceNumber) {

        sender ! MessageReceivedConfirmation(deliveryId)

      }

      if (sequenceNumber == nextExpectedMessageSequenceNumber) {

        nextExpectedMessageSequenceNumbers = nextExpectedMessageSequenceNumbers + (sender() -> (sequenceNumber + 1))

        upstream ! Json.stringify(
          Json.toJson(
            Notification.write(notification)
          )
        )

        keepAliveActor ! FrameSentEvent

      }

  }

  override def receiveRecover = Map.empty

}
