package actors

import java.util.UUID

import actors.ClientConnection._
import akka.actor._
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Publish
import akka.persistence.{AtLeastOnceDelivery, PersistentActor}
import com.dhpcs.jsonrpc._
import com.dhpcs.liquidity.models._
import play.api.libs.json._

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object ClientConnection {

  def props(publicKey: PublicKey, zoneValidatorShardRegion: ActorRef)(upstream: ActorRef) =
    Props(new ClientConnection(publicKey, zoneValidatorShardRegion, upstream))

  val Topic = "Client"

  case class MessageReceivedConfirmation(deliveryId: Long)

  case class ActiveClientSummary(publicKey: PublicKey)

  private case object PublishStatus

  private class KeepAliveGenerator extends Actor {

    import actors.ClientConnection.KeepAliveGenerator._

    context.setReceiveTimeout(keepAliveInterval)

    override def receive: Receive = {

      case ReceiveTimeout =>

        context.parent ! SendKeepAlive

      case FrameReceivedEvent | FrameSentEvent =>

    }

  }

  private object KeepAliveGenerator {

    private val keepAliveInterval = 30.seconds

    case object FrameReceivedEvent

    case object FrameSentEvent

    case object SendKeepAlive

  }

  private def readCommand(jsonString: String):
  (Option[Either[String, BigDecimal]], Either[JsonRpcResponseError, Command]) =

    Try(Json.parse(jsonString)) match {

      case Failure(exception) =>

        None -> Left(
          JsonRpcResponseError.parseError(exception)
        )

      case Success(json) =>

        Json.fromJson[JsonRpcRequestMessage](json).fold(

          errors => None -> Left(
            JsonRpcResponseError.invalidRequest(errors)
          ),

          jsonRpcRequestMessage =>

            Command.read(jsonRpcRequestMessage)
              .fold[(Option[Either[String, BigDecimal]], Either[JsonRpcResponseError, Command])](

              jsonRpcRequestMessage.id -> Left(
                JsonRpcResponseError.methodNotFound(jsonRpcRequestMessage.method)
              )

            )(commandJsResult => commandJsResult.fold(

              errors => jsonRpcRequestMessage.id -> Left(
                JsonRpcResponseError.invalidParams(errors)
              ),

              command => jsonRpcRequestMessage.id -> Right(
                command
              )

            ))

        )

    }

}

class ClientConnection(publicKey: PublicKey,
                       zoneValidatorShardRegion: ActorRef,
                       upstream: ActorRef) extends PersistentActor with ActorLogging with AtLeastOnceDelivery {

  import actors.ClientConnection.KeepAliveGenerator._
  import context.dispatcher

  private val mediator = DistributedPubSub(context.system).mediator

  private val publishStatusTick = context.system.scheduler.schedule(0.seconds, 30.seconds, self, PublishStatus)

  private val keepAliveActor = context.actorOf(Props[KeepAliveGenerator])

  private var nextExpectedMessageSequenceNumbers = Map.empty[ActorRef, Long].withDefaultValue(0L)
  private var commandSequenceNumbers = Map.empty[ZoneId, Long].withDefaultValue(0L)
  private var pendingDeliveries = Map.empty[ZoneId, Set[Long]].withDefaultValue(Set.empty)

  upstream ! Json.stringify(Json.toJson(
    Notification.write(
      SupportedVersionsNotification(
        CompatibleVersionNumbers
      )
    )
  ))

  private def createZone(createZoneCommand: CreateZoneCommand, correlationId: Option[Either[String, BigDecimal]]) {
    val zoneId = ZoneId.generate
    val sequenceNumber = commandSequenceNumbers(zoneId)
    commandSequenceNumbers = commandSequenceNumbers + (zoneId -> (sequenceNumber + 1))
    deliver(zoneValidatorShardRegion.path) { deliveryId =>
      pendingDeliveries = pendingDeliveries + (zoneId -> (pendingDeliveries(zoneId) + deliveryId))
      ZoneValidator.EnvelopedMessage(
        zoneId,
        ZoneValidator.AuthenticatedCommandWithIds(
          publicKey,
          createZoneCommand,
          correlationId,
          sequenceNumber,
          deliveryId
        )
      )
    }
  }

  override def persistenceId = s"${publicKey.productPrefix}(${publicKey.fingerprint})"

  override def postStop() {
    log.info(s"Stopped actor for ${publicKey.fingerprint}")
    publishStatusTick.cancel()
    super.postStop()
  }

  override def preStart() {
    log.info(s"Started actor for ${publicKey.fingerprint}")
    super.preStart()
  }

  override def receiveCommand = {

    case PublishStatus =>

      mediator ! Publish(
        Topic,
        ActiveClientSummary(
          publicKey
        )
      )

    case SendKeepAlive =>

      upstream ! Json.stringify(
        Json.toJson(
          Notification.write(KeepAliveNotification)
        )
      )

    case json: String =>

      keepAliveActor ! FrameReceivedEvent

      readCommand(json) match {

        case (id, Left(jsonRpcResponseError)) =>

          log.warning(s"Receive error: $jsonRpcResponseError")

          sender ! Json.stringify(Json.toJson(
            JsonRpcResponseMessage(
              Left(
                jsonRpcResponseError
              ),
              id
            )
          ))

        case (correlationId, Right(command)) =>

          command match {

            case createZoneCommand: CreateZoneCommand =>

              createZone(createZoneCommand, correlationId)

            case zoneCommand: ZoneCommand =>

              val zoneId = zoneCommand.zoneId

              val sequenceNumber = commandSequenceNumbers(zoneId)
              commandSequenceNumbers = commandSequenceNumbers + (zoneId -> (sequenceNumber + 1))
              deliver(zoneValidatorShardRegion.path) { deliveryId =>
                pendingDeliveries = pendingDeliveries + (zoneId -> (pendingDeliveries(zoneId) + deliveryId))
                ZoneValidator.AuthenticatedCommandWithIds(
                  publicKey,
                  zoneCommand,
                  correlationId,
                  sequenceNumber,
                  deliveryId
                )
              }

          }

      }

    case ZoneValidator.ZoneAlreadyExists(createZoneCommand, correlationId, sequenceNumber, deliveryId) =>

      val nextExpectedMessageSequenceNumber = nextExpectedMessageSequenceNumbers(sender())

      if (sequenceNumber <= nextExpectedMessageSequenceNumber) {

        sender ! MessageReceivedConfirmation(deliveryId)

      }

      if (sequenceNumber == nextExpectedMessageSequenceNumber) {

        nextExpectedMessageSequenceNumbers = nextExpectedMessageSequenceNumbers + (sender() -> (sequenceNumber + 1))

        createZone(createZoneCommand, correlationId)

      }

    case ZoneValidator.ZoneRestarted(zoneId, sequenceNumber, deliveryId) =>

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

        upstream ! Json.stringify(Json.toJson(
          Notification.write(
            ZoneTerminatedNotification(
              zoneId
            )
          )
        ))

        keepAliveActor ! FrameSentEvent

      }

    case ZoneValidator.CommandReceivedConfirmation(zoneId, deliveryId) =>

      confirmDelivery(deliveryId)

      pendingDeliveries = pendingDeliveries + (zoneId -> (pendingDeliveries(zoneId) - deliveryId))

      if (pendingDeliveries(zoneId).isEmpty) {

        pendingDeliveries = pendingDeliveries - zoneId

      }

    case ZoneValidator.ResponseWithIds(response, correlationId, sequenceNumber, deliveryId) =>

      val nextExpectedMessageSequenceNumber = nextExpectedMessageSequenceNumbers(sender())

      if (sequenceNumber <= nextExpectedMessageSequenceNumber) {

        sender ! MessageReceivedConfirmation(deliveryId)

      }

      if (sequenceNumber == nextExpectedMessageSequenceNumber) {

        nextExpectedMessageSequenceNumbers = nextExpectedMessageSequenceNumbers + (sender() -> (sequenceNumber + 1))

        upstream ! Json.stringify(Json.toJson(
          Response.write(
            response,
            correlationId
          )
        ))

        keepAliveActor ! FrameSentEvent

      }

    case ZoneValidator.NotificationWithIds(notification, sequenceNumber, deliveryId) =>

      val nextExpectedMessageSequenceNumber = nextExpectedMessageSequenceNumbers(sender())

      if (sequenceNumber <= nextExpectedMessageSequenceNumber) {

        sender ! MessageReceivedConfirmation(deliveryId)

      }

      if (sequenceNumber == nextExpectedMessageSequenceNumber) {

        nextExpectedMessageSequenceNumbers = nextExpectedMessageSequenceNumbers + (sender() -> (sequenceNumber + 1))

        upstream ! Json.stringify(Json.toJson(
          Notification.write(
            notification
          )
        ))

        keepAliveActor ! FrameSentEvent

      }

  }

  override def receiveRecover = Map.empty

}
