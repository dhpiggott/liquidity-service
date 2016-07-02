package actors

import java.util.UUID

import actors.ClientConnection._
import akka.actor.{Actor, ActorLogging, ActorRef, ActorRefFactory, OneForOneStrategy, PoisonPill, Props, ReceiveTimeout, Status, SupervisorStrategy, Terminated}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Publish
import akka.http.scaladsl.model.RemoteAddress
import akka.http.scaladsl.model.ws.{TextMessage, Message => WsMessage}
import akka.pattern.pipe
import akka.persistence.{AtLeastOnceDelivery, PersistentActor}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}
import com.dhpcs.jsonrpc.{JsonRpcRequestMessage, JsonRpcResponseError, JsonRpcResponseMessage}
import com.dhpcs.liquidity.models._
import play.api.libs.json.Json

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object ClientConnection {
  def webSocketFlow(ip: RemoteAddress,
                    publicKey: PublicKey,
                    zoneValidatorShardRegion: ActorRef)
                   (implicit factory: ActorRefFactory, materializer: Materializer): Flow[WsMessage, WsMessage, Any] =
    actorRef(
      props = props(ip, publicKey, zoneValidatorShardRegion),
      name = publicKey.fingerprint,
      overflowStrategy = OverflowStrategy.fail
    )

  def props(ip: RemoteAddress,
            publicKey: PublicKey,
            zoneValidatorShardRegion: ActorRef)
           (upstream: ActorRef)
           (implicit materializer: Materializer): Props =
    Props(
      new ClientConnection(
        ip,
        publicKey,
        zoneValidatorShardRegion,
        upstream
      )
    )

  final val Topic = "Client"

  case class MessageReceivedConfirmation(deliveryId: Long)

  case class ActiveClientSummary(publicKey: PublicKey)

  private case object PublishStatus

  private object KeepAliveGenerator {
    def props: Props = Props(new KeepAliveGenerator)

    case object FrameReceivedEvent

    case object FrameSentEvent

    case object SendKeepAlive

    private final val KeepAliveInterval = 30.seconds
  }

  private class KeepAliveGenerator extends Actor {

    import actors.ClientConnection.KeepAliveGenerator._

    context.setReceiveTimeout(KeepAliveInterval)

    override def receive: Receive = {
      case ReceiveTimeout =>
        context.parent ! SendKeepAlive
      case FrameReceivedEvent | FrameSentEvent =>
    }
  }

  private def actorRef[In, Out](props: ActorRef => Props,
                                name: String,
                                bufferSize: Int = 16,
                                overflowStrategy: OverflowStrategy = OverflowStrategy.dropNew)
                               (implicit factory: ActorRefFactory, materializer: Materializer): Flow[In, Out, _] = {
    val (outActor, publisher) = Source.actorRef[Out](bufferSize, overflowStrategy)
      .toMat(Sink.asPublisher(false))(Keep.both).run()
    Flow.fromSinkAndSource(
      Sink.actorRef(factory.actorOf(Props(new Actor {
        val flowActor = context.watch(context.actorOf(props(outActor), name))

        override def receive = {
          case Status.Success(_) | Status.Failure(_) =>
            flowActor ! PoisonPill
            outActor ! Status.Success(())
          case Terminated =>
            println("Child terminated, stopping")
            context.stop(self)
          case other =>
            flowActor ! other
        }

        override def supervisorStrategy = OneForOneStrategy() {
          case _ =>
            println("Stopping actor due to exception")
            SupervisorStrategy.Stop
        }
      })), Status.Success(())),
      Source.fromPublisher(publisher)
    )
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

class ClientConnection(ip: RemoteAddress,
                       publicKey: PublicKey,
                       zoneValidatorShardRegion: ActorRef,
                       upstream: ActorRef)
                      (implicit materializer: Materializer)
  extends PersistentActor
    with ActorLogging
    with AtLeastOnceDelivery {

  import actors.ClientConnection.KeepAliveGenerator._
  import context.dispatcher

  private[this] val mediator = DistributedPubSub(context.system).mediator
  private[this] val publishStatusTick = context.system.scheduler.schedule(0.seconds, 30.seconds, self, PublishStatus)
  private[this] val keepAliveActor = context.actorOf(KeepAliveGenerator.props)

  private[this] var nextExpectedMessageSequenceNumbers = Map.empty[ActorRef, Long].withDefaultValue(0L)
  private[this] var commandSequenceNumbers = Map.empty[ZoneId, Long].withDefaultValue(0L)
  private[this] var pendingDeliveries = Map.empty[ZoneId, Set[Long]].withDefaultValue(Set.empty)

  override def persistenceId: String = s"${publicKey.productPrefix}(${publicKey.fingerprint})"

  override def preStart(): Unit = {
    super.preStart()
    upstream ! TextMessage.Strict(Json.stringify(Json.toJson(
      Notification.write(
        SupportedVersionsNotification(
          CompatibleVersionNumbers
        )
      )
    )))
    log.info(s"Started actor for ${ip.toOption.getOrElse("unknown")} (${publicKey.fingerprint})")
  }

  override def postStop(): Unit = {
    log.info(s"Stopped actor for ${ip.toOption.getOrElse("unknown")} (${publicKey.fingerprint})")
    publishStatusTick.cancel()
    super.postStop()
  }

  override def receiveCommand: Receive = {
    case PublishStatus =>
      mediator ! Publish(
        Topic,
        ActiveClientSummary(
          publicKey
        )
      )
    case SendKeepAlive =>
      upstream ! TextMessage.Strict(Json.stringify(
        Json.toJson(
          Notification.write(KeepAliveNotification)
        )
      ))
    case TextMessage.Streamed(jsonStream) =>
      val json = jsonStream.runFold("")(_ + _)
      // We don't expect to receive streamed messages, but it's worth handling them just in case.
      json.pipeTo(self)
    case TextMessage.Strict(json) =>
      keepAliveActor ! FrameReceivedEvent
      readCommand(json) match {
        case (id, Left(jsonRpcResponseError)) =>
          log.warning(s"Receive error: $jsonRpcResponseError")
          sender() ! TextMessage.Strict(Json.stringify(Json.toJson(
            JsonRpcResponseMessage(
              Left(
                jsonRpcResponseError
              ),
              id
            )
          )))
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
        sender() ! MessageReceivedConfirmation(deliveryId)
      }
      if (sequenceNumber == nextExpectedMessageSequenceNumber) {
        nextExpectedMessageSequenceNumbers = nextExpectedMessageSequenceNumbers + (sender() -> (sequenceNumber + 1))
        createZone(createZoneCommand, correlationId)
      }
    case ZoneValidator.ZoneRestarted(zoneId, sequenceNumber, deliveryId) =>
      val nextExpectedMessageSequenceNumber = nextExpectedMessageSequenceNumbers(sender())
      if (sequenceNumber <= nextExpectedMessageSequenceNumber) {
        sender() ! MessageReceivedConfirmation(deliveryId)
      }
      if (sequenceNumber == nextExpectedMessageSequenceNumber) {
        // Remove previous validator entry, add new validator entry.
        nextExpectedMessageSequenceNumbers = nextExpectedMessageSequenceNumbers.filterKeys { validator =>
          ZoneId(UUID.fromString(validator.path.name)) != zoneId
        } + (sender() -> (sequenceNumber + 1))
        commandSequenceNumbers = commandSequenceNumbers - zoneId
        pendingDeliveries(zoneId).foreach(confirmDelivery)
        pendingDeliveries = pendingDeliveries - zoneId
        upstream ! TextMessage.Strict(Json.stringify(Json.toJson(
          Notification.write(
            ZoneTerminatedNotification(
              zoneId
            )
          )
        )))
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
        sender() ! MessageReceivedConfirmation(deliveryId)
      }
      if (sequenceNumber == nextExpectedMessageSequenceNumber) {
        nextExpectedMessageSequenceNumbers = nextExpectedMessageSequenceNumbers + (sender() -> (sequenceNumber + 1))
        upstream ! TextMessage.Strict(Json.stringify(Json.toJson(
          Response.write(
            response,
            correlationId
          )
        )))
        keepAliveActor ! FrameSentEvent
      }
    case ZoneValidator.NotificationWithIds(notification, sequenceNumber, deliveryId) =>
      val nextExpectedMessageSequenceNumber = nextExpectedMessageSequenceNumbers(sender())
      if (sequenceNumber <= nextExpectedMessageSequenceNumber) {
        sender() ! MessageReceivedConfirmation(deliveryId)
      }
      if (sequenceNumber == nextExpectedMessageSequenceNumber) {
        nextExpectedMessageSequenceNumbers = nextExpectedMessageSequenceNumbers + (sender() -> (sequenceNumber + 1))
        upstream ! TextMessage.Strict(Json.stringify(Json.toJson(
          Notification.write(
            notification
          )
        )))
        keepAliveActor ! FrameSentEvent
      }
  }

  override def receiveRecover: Receive = PartialFunction.empty

  private[this] def createZone(createZoneCommand: CreateZoneCommand,
                               correlationId: Option[Either[String, BigDecimal]]): Unit = {
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
}
