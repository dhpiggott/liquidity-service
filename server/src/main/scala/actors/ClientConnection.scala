package actors

import java.util.UUID

import actors.ClientConnection._
import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef, ActorRefFactory, OneForOneStrategy, PoisonPill, Props, ReceiveTimeout, Status, SupervisorStrategy, Terminated}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Publish
import akka.http.scaladsl.model.RemoteAddress
import akka.http.scaladsl.model.ws.{BinaryMessage, TextMessage, Message => WsMessage}
import akka.persistence.{AtLeastOnceDelivery, PersistentActor}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}
import akka.util.ByteString
import com.dhpcs.jsonrpc.ResponseCompanion.ErrorResponse
import com.dhpcs.jsonrpc.{JsonRpcMessage, JsonRpcRequestMessage, JsonRpcResponseError, JsonRpcResponseMessage}
import com.dhpcs.liquidity.models._
import play.api.libs.json.Json

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object ClientConnection {
  def props(ip: RemoteAddress,
            publicKey: PublicKey,
            zoneValidatorShardRegion: ActorRef)
           (upstream: ActorRef)
           (implicit mat: Materializer): Props =
    Props(
      new ClientConnection(
        ip,
        publicKey,
        zoneValidatorShardRegion,
        upstream
      )
    )

  def webSocketFlow(ip: RemoteAddress,
                    publicKey: PublicKey,
                    zoneValidatorShardRegion: ActorRef)
                   (implicit factory: ActorRefFactory, mat: Materializer): Flow[WsMessage, WsMessage, NotUsed] =
    wsMessageToString.via(
      actorFlow[String, String](
        props = props(ip, publicKey, zoneValidatorShardRegion),
        name = publicKey.fingerprint,
        overflowStrategy = OverflowStrategy.fail
      )
    ).via(
      stringToWsMessage
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

  private def actorFlow[In, Out](props: ActorRef => Props,
                                 name: String,
                                 bufferSize: Int = 16,
                                 overflowStrategy: OverflowStrategy = OverflowStrategy.dropNew)
                                (implicit factory: ActorRefFactory, mat: Materializer): Flow[In, Out, NotUsed] = {
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

  private def wsMessageToString(implicit mat: Materializer): Flow[WsMessage, String, NotUsed] =
    Flow[WsMessage].mapAsync[String](1) {
      case TextMessage.Strict(text) =>
        Future.successful(text)
      case TextMessage.Streamed(textStream) =>
        textStream.runFold("")(_ ++ _)
      case BinaryMessage.Strict(data) =>
        Future.successful(data.utf8String)
      case BinaryMessage.Streamed(byteStream) =>
        import mat.executionContext
        byteStream.runFold(ByteString.empty)(_ ++ _).map(_.utf8String)
    }

  private def stringToWsMessage: Flow[String, WsMessage, NotUsed] =
    Flow[String].map[WsMessage](TextMessage.Strict)

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
                      (implicit mat: Materializer)
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
    send(SupportedVersionsNotification(CompatibleVersionNumbers))
    log.info(s"Started actor for ${ip.toOption.getOrElse("unknown")} (${publicKey.fingerprint})")
  }

  override def postStop(): Unit = {
    log.info(s"Stopped actor for ${ip.toOption.getOrElse("unknown")} (${publicKey.fingerprint})")
    publishStatusTick.cancel()
    super.postStop()
  }

  override def receiveCommand: Receive =
    publishStatus orElse commandReceivedConfirmation orElse sendKeepAlive orElse {
      case jsonString: String =>
        keepAliveActor ! FrameReceivedEvent
        readCommand(jsonString) match {
          case (id, Left(jsonRpcResponseError)) =>
            log.warning(s"Receive error: $jsonRpcResponseError")
            send(JsonRpcResponseMessage(
              Left(jsonRpcResponseError),
              id
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
        exactlyOnce(sequenceNumber, deliveryId)(
          createZone(createZoneCommand, correlationId)
        )
      case ZoneValidator.ResponseWithIds(response, correlationId, sequenceNumber, deliveryId) =>
        exactlyOnce(sequenceNumber, deliveryId)(
          send(response, correlationId)
        )
      case ZoneValidator.NotificationWithIds(notification, sequenceNumber, deliveryId) =>
        exactlyOnce(sequenceNumber, deliveryId)(
          send(notification)
        )
      case ZoneValidator.ZoneRestarted(zoneId, sequenceNumber, deliveryId) =>
        exactlyOnce(sequenceNumber, deliveryId) {
          // Remove previous validator entry, add new validator entry.
          nextExpectedMessageSequenceNumbers = nextExpectedMessageSequenceNumbers.filterKeys(validator =>
            ZoneId(UUID.fromString(validator.path.name)) != zoneId
          ) + (sender() -> (sequenceNumber + 1))
          commandSequenceNumbers = commandSequenceNumbers - zoneId
          pendingDeliveries(zoneId).foreach(confirmDelivery)
          pendingDeliveries = pendingDeliveries - zoneId
          send(ZoneTerminatedNotification(zoneId))
        }
    }

  override def receiveRecover: Receive = Actor.emptyBehavior

  private[this] def publishStatus: Receive = {
    case PublishStatus =>
      mediator ! Publish(
        Topic,
        ActiveClientSummary(publicKey)
      )
  }

  private[this] def commandReceivedConfirmation: Receive = {
    case ZoneValidator.CommandReceivedConfirmation(zoneId, deliveryId) =>
      confirmDelivery(deliveryId)
      pendingDeliveries = pendingDeliveries + (zoneId -> (pendingDeliveries(zoneId) - deliveryId))
      if (pendingDeliveries(zoneId).isEmpty) {
        pendingDeliveries = pendingDeliveries - zoneId
      }
  }

  private[this] def sendKeepAlive: Receive = {
    case SendKeepAlive =>
      send(KeepAliveNotification)
  }

  private[this] def exactlyOnce(sequenceNumber: Long, deliveryId: Long)(body: => Unit): Unit = {
    val nextExpectedMessageSequenceNumber = nextExpectedMessageSequenceNumbers(sender())
    if (sequenceNumber <= nextExpectedMessageSequenceNumber) {
      sender() ! MessageReceivedConfirmation(deliveryId)
    }
    if (sequenceNumber == nextExpectedMessageSequenceNumber) {
      nextExpectedMessageSequenceNumbers = nextExpectedMessageSequenceNumbers + (sender() -> (sequenceNumber + 1))
      body
    }
  }

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

  private[this] def send(response: Either[ErrorResponse, ResultResponse],
                         correlationId: Option[Either[String, BigDecimal]]): Unit =
    send(Response.write(
      response,
      correlationId
    ))

  private[this] def send(notification: Notification): Unit =
    send(Notification.write(
      notification
    ))

  private[this] def send(jsonRpcMessage: JsonRpcMessage): Unit = {
    upstream ! Json.stringify(Json.toJson(
      jsonRpcMessage
    ))
    keepAliveActor ! FrameSentEvent
  }
}