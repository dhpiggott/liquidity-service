package com.dhpcs.liquidity.server.actor

import java.util.UUID

import akka.NotUsed
import akka.actor.{
  Actor,
  ActorLogging,
  ActorRef,
  ActorRefFactory,
  Deploy,
  NoSerializationVerificationNeeded,
  PoisonPill,
  Props,
  ReceiveTimeout,
  Status,
  SupervisorStrategy,
  Terminated
}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Publish
import akka.http.scaladsl.model.RemoteAddress
import akka.http.scaladsl.model.ws.{BinaryMessage, TextMessage, Message => WsMessage}
import akka.persistence.{AtLeastOnceDelivery, PersistentActor}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}
import com.dhpcs.jsonrpc.ResponseCompanion.ErrorResponse
import com.dhpcs.jsonrpc.{JsonRpcMessage, JsonRpcRequestMessage, JsonRpcResponseError, JsonRpcResponseMessage}
import com.dhpcs.liquidity.actor.protocol._
import com.dhpcs.liquidity.model.{PublicKey, ZoneId}
import com.dhpcs.liquidity.persistence.RichPublicKey
import com.dhpcs.liquidity.server.actor.ClientConnectionActor._
import com.dhpcs.liquidity.ws.protocol._
import play.api.libs.json.{JsError, JsSuccess, Json}

import scala.concurrent.duration._

object ClientConnectionActor {

  def props(ip: RemoteAddress,
            publicKey: PublicKey,
            zoneValidatorShardRegion: ActorRef,
            keepAliveInterval: FiniteDuration)(upstream: ActorRef): Props =
    Props(
      new ClientConnectionActor(
        ip,
        publicKey,
        zoneValidatorShardRegion,
        keepAliveInterval,
        upstream
      )
    )

  def webSocketFlow(ip: RemoteAddress,
                    publicKey: PublicKey,
                    zoneValidatorShardRegion: ActorRef,
                    keepAliveInterval: FiniteDuration)(implicit factory: ActorRefFactory,
                                                       mat: Materializer): Flow[WsMessage, WsMessage, NotUsed] =
    WsMessageToWrappedJsonRpcMessageFlow
      .via(
        actorFlow[WrappedJsonRpcMessage, WrappedJsonRpcMessage](
          props = props(ip, publicKey, zoneValidatorShardRegion, keepAliveInterval),
          name = publicKey.fingerprint
        )
      )
      .via(
        WrappedJsonRpcMessageToWsMessageFlow
      )

  final val WsMessageToWrappedJsonRpcMessageFlow: Flow[WsMessage, WrappedJsonRpcMessage, NotUsed] =
    Flow[WsMessage]
      .flatMapConcat {
        case _: BinaryMessage                 => sys.error(s"Received binary message")
        case TextMessage.Streamed(textStream) => textStream.fold("")(_ + _)
        case TextMessage.Strict(text)         => Source.single(text)
      }
      .map(jsValue =>
        Json.parse(jsValue).validate[JsonRpcMessage] match {
          case JsError(errors) =>
            sys.error(s"Failed to parse $jsValue as JSON-RPC message with errors: $errors")
          case JsSuccess(jsonRpcMessage, _) => WrappedJsonRpcMessage(jsonRpcMessage)
      })

  final val WrappedJsonRpcMessageToWsMessageFlow: Flow[WrappedJsonRpcMessage, WsMessage, NotUsed] =
    Flow[WrappedJsonRpcMessage].map(
      wrappedJsonRpcMessage =>
        TextMessage.Strict(
          Json.stringify(
            Json.toJson(
              wrappedJsonRpcMessage.jsonRpcMessage
            ))
      ))

  final val Topic = "Client"

  case class WrappedJsonRpcMessage(jsonRpcMessage: JsonRpcMessage) extends NoSerializationVerificationNeeded

  case object ActorSinkInit

  case object ActorSinkAck

  private case object PublishStatus

  private object KeepAliveGeneratorActor {

    def props(keepAliveInterval: FiniteDuration): Props = Props(new KeepAliveGeneratorActor(keepAliveInterval))

    case object FrameReceivedEvent

    case object FrameSentEvent

    case object SendKeepAlive

  }

  private class KeepAliveGeneratorActor(keepAliveInterval: FiniteDuration) extends Actor {

    import com.dhpcs.liquidity.server.actor.ClientConnectionActor.KeepAliveGeneratorActor._

    context.setReceiveTimeout(keepAliveInterval)

    override def receive: Receive = {
      case ReceiveTimeout =>
        context.parent ! SendKeepAlive
      case FrameReceivedEvent | FrameSentEvent =>
    }
  }

  private def actorFlow[In, Out](props: ActorRef => Props, name: String)(implicit factory: ActorRefFactory,
                                                                         mat: Materializer): Flow[In, Out, NotUsed] = {
    val (outActor, publisher) = Source
      .actorRef[Out](bufferSize = 16, overflowStrategy = OverflowStrategy.fail)
      .toMat(Sink.asPublisher(false))(Keep.both)
      .run()
    Flow.fromSinkAndSource(
      Sink.actorRefWithAck(
        factory.actorOf(
          Props(new Actor {
            val flowActor: ActorRef                             = context.watch(context.actorOf(props(outActor).withDeploy(Deploy.local), name))
            override def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy
            override def receive: Receive = {
              case _: Status.Success | _: Status.Failure =>
                flowActor ! PoisonPill
              case _: Terminated =>
                context.stop(self)
                outActor ! Status.Success(())
              case other =>
                flowActor.forward(other)
            }
          }).withDeploy(Deploy.local)
        ),
        onInitMessage = ActorSinkInit,
        ackMessage = ActorSinkAck,
        onCompleteMessage = Status.Success(())
      ),
      Source.fromPublisher(publisher)
    )
  }

  private def readCommand(jsonRpcRequestMessage: JsonRpcRequestMessage)
    : (Option[Either[String, BigDecimal]], Either[JsonRpcResponseError, Command]) =
    Command.read(jsonRpcRequestMessage) match {
      case None =>
        jsonRpcRequestMessage.id -> Left(
          JsonRpcResponseError.methodNotFound(jsonRpcRequestMessage.method)
        )
      case Some(JsError(errors)) =>
        jsonRpcRequestMessage.id -> Left(
          JsonRpcResponseError.invalidParams(errors)
        )
      case Some(JsSuccess(command, _)) =>
        jsonRpcRequestMessage.id -> Right(
          command
        )
    }
}

class ClientConnectionActor(ip: RemoteAddress,
                            publicKey: PublicKey,
                            zoneValidatorShardRegion: ActorRef,
                            keepAliveInterval: FiniteDuration,
                            upstream: ActorRef)
    extends PersistentActor
    with ActorLogging
    with AtLeastOnceDelivery {

  import com.dhpcs.liquidity.server.actor.ClientConnectionActor.KeepAliveGeneratorActor._
  import context.dispatcher

  private[this] val mediator          = DistributedPubSub(context.system).mediator
  private[this] val publishStatusTick = context.system.scheduler.schedule(0.seconds, 30.seconds, self, PublishStatus)
  private[this] val keepAliveGeneratorActor =
    context.actorOf(KeepAliveGeneratorActor.props(keepAliveInterval).withDeploy(Deploy.local))

  private[this] var nextExpectedMessageSequenceNumbers = Map.empty[ActorRef, Long].withDefaultValue(1L)
  private[this] var commandSequenceNumbers             = Map.empty[ZoneId, Long].withDefaultValue(1L)
  private[this] var pendingDeliveries                  = Map.empty[ZoneId, Set[Long]].withDefaultValue(Set.empty)

  override def persistenceId: String = publicKey.persistenceId

  override def preStart(): Unit = {
    super.preStart()
    send(SupportedVersionsNotification(CompatibleVersionNumbers))
    log.info(s"Started for ${ip.toOption.getOrElse("unknown IP")}")
  }

  override def postStop(): Unit = {
    publishStatusTick.cancel()
    super.postStop()
    log.info(s"Stopped for ${ip.toOption.getOrElse("unknown IP")}")
  }

  override def receiveCommand: Receive = waitingForActorSinkInit

  private[this] def waitingForActorSinkInit: Receive =
    publishStatus orElse sendKeepAlive orElse {
      case ActorSinkInit =>
        sender() ! ActorSinkAck
        context.become(receiveActorSinkMessages)
    }

  private[this] def receiveActorSinkMessages: Receive =
    publishStatus orElse commandReceivedConfirmation orElse sendKeepAlive orElse {
      case WrappedJsonRpcMessage(jsonRpcRequestMessage: JsonRpcRequestMessage) =>
        sender() ! ActorSinkAck
        keepAliveGeneratorActor ! FrameReceivedEvent
        readCommand(jsonRpcRequestMessage) match {
          case (id, Left(jsonRpcResponseError)) =>
            log.warning(s"Receive error: $jsonRpcResponseError")
            send(
              JsonRpcResponseMessage(
                Left(jsonRpcResponseError),
                id
              ))
          case (correlationId, Right(command)) =>
            command match {
              case createZoneCommand: CreateZoneCommand =>
                createZone(createZoneCommand, correlationId)
              case zoneCommand: ZoneCommand =>
                val zoneId         = zoneCommand.zoneId
                val sequenceNumber = commandSequenceNumbers(zoneId)
                commandSequenceNumbers = commandSequenceNumbers + (zoneId -> (sequenceNumber + 1))
                deliver(zoneValidatorShardRegion.path) { deliveryId =>
                  pendingDeliveries = pendingDeliveries + (zoneId -> (pendingDeliveries(zoneId) + deliveryId))
                  AuthenticatedCommandWithIds(
                    publicKey,
                    zoneCommand,
                    correlationId,
                    sequenceNumber,
                    deliveryId
                  )
                }
            }
        }
      case ZoneAlreadyExists(createZoneCommand, correlationId, sequenceNumber, deliveryId) =>
        exactlyOnce(sequenceNumber, deliveryId)(
          createZone(createZoneCommand, correlationId)
        )
      case ResponseWithIds(response, correlationId, sequenceNumber, deliveryId) =>
        exactlyOnce(sequenceNumber, deliveryId)(
          send(response, correlationId)
        )
      case NotificationWithIds(notification, sequenceNumber, deliveryId) =>
        exactlyOnce(sequenceNumber, deliveryId)(
          send(notification)
        )
      case ZoneRestarted(zoneId) =>
        nextExpectedMessageSequenceNumbers = nextExpectedMessageSequenceNumbers.filterKeys(validator =>
          ZoneId(UUID.fromString(validator.path.name)) != zoneId)
        commandSequenceNumbers = commandSequenceNumbers - zoneId
        pendingDeliveries(zoneId).foreach(confirmDelivery)
        pendingDeliveries = pendingDeliveries - zoneId
        send(ZoneTerminatedNotification(zoneId))
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
    case CommandReceivedConfirmation(zoneId, deliveryId) =>
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
    val zoneId         = ZoneId.generate
    val sequenceNumber = commandSequenceNumbers(zoneId)
    commandSequenceNumbers = commandSequenceNumbers + (zoneId -> (sequenceNumber + 1))
    deliver(zoneValidatorShardRegion.path) { deliveryId =>
      pendingDeliveries = pendingDeliveries + (zoneId -> (pendingDeliveries(zoneId) + deliveryId))
      EnvelopedAuthenticatedCommandWithIds(
        zoneId,
        AuthenticatedCommandWithIds(
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
    send(
      Response.write(
        response,
        correlationId
      ))

  private[this] def send(notification: Notification): Unit =
    send(
      Notification.write(
        notification
      ))

  private[this] def send(jsonRpcMessage: JsonRpcMessage): Unit = {
    upstream ! WrappedJsonRpcMessage(jsonRpcMessage)
    keepAliveGeneratorActor ! FrameSentEvent
  }
}
