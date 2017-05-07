package com.dhpcs.liquidity.server.actor

import java.util.UUID

import akka.NotUsed
import akka.actor._
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Publish
import akka.http.scaladsl.model.RemoteAddress
import akka.http.scaladsl.model.ws.{TextMessage, Message => WsMessage}
import akka.persistence.{AtLeastOnceDelivery, PersistentActor}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}
import com.dhpcs.jsonrpc.JsonRpcMessage.{CorrelationId, NoCorrelationId, NumericCorrelationId, StringCorrelationId}
import com.dhpcs.jsonrpc._
import com.dhpcs.liquidity.actor.protocol._
import com.dhpcs.liquidity.model.{PublicKey, ZoneId}
import com.dhpcs.liquidity.persistence.RichPublicKey
import com.dhpcs.liquidity.serialization.ProtoConverter
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

  def webSocketFlow(props: ActorRef => Props, name: String)(implicit factory: ActorRefFactory,
                                                            mat: Materializer): Flow[WsMessage, WsMessage, NotUsed] =
    InFlow
      .via(actorFlow[WrappedJsonRpcOrProtobufCommand, WrappedJsonRpcOrProtobufResponseOrNotification](props, name))
      .via(OutFlow)

  private final val InFlow: Flow[WsMessage, WrappedJsonRpcOrProtobufCommand, NotUsed] =
    Flow[WsMessage]
      .flatMapConcat(wsMessage =>
        for (jsonString <- wsMessage.asTextMessage match {
               case TextMessage.Streamed(textStream) => textStream.fold("")(_ + _)
               case TextMessage.Strict(text)         => Source.single(text)
             }) yield WrappedJsonRpcRequest(Json.parse(jsonString).as[JsonRpcRequestMessage]))

  private final val OutFlow: Flow[WrappedJsonRpcOrProtobufResponseOrNotification, WsMessage, NotUsed] =
    Flow[WrappedJsonRpcOrProtobufResponseOrNotification].map {
      case WrappedJsonRpcResponse(jsonRpcResponseMessage) =>
        TextMessage(Json.stringify(Json.toJson(jsonRpcResponseMessage)))
      case WrappedJsonRpcNotification(jsonRpcNotificationMessage) =>
        TextMessage(Json.stringify(Json.toJson(jsonRpcNotificationMessage)))
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

  final val Topic = "Client"

  sealed abstract class WrappedJsonRpcOrProtobufCommand extends NoSerializationVerificationNeeded
  final case class WrappedJsonRpcRequest(jsonRpcRequestMessage: JsonRpcRequestMessage)
      extends WrappedJsonRpcOrProtobufCommand

  sealed abstract class WrappedJsonRpcOrProtobufResponseOrNotification extends NoSerializationVerificationNeeded
  final case class WrappedJsonRpcResponse(jsonRpcResponseMessage: JsonRpcResponseMessage)
      extends WrappedJsonRpcOrProtobufResponseOrNotification
  final case class WrappedJsonRpcNotification(jsonRpcNotificationMessage: JsonRpcNotificationMessage)
      extends WrappedJsonRpcOrProtobufResponseOrNotification

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
      case ReceiveTimeout                      => context.parent ! SendKeepAlive
      case FrameReceivedEvent | FrameSentEvent => ()
    }
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
    log.info(s"Started for ${ip.toOption.getOrElse("unknown IP")}")
  }

  override def postStop(): Unit = {
    publishStatusTick.cancel()
    super.postStop()
    log.info(s"Stopped for ${ip.toOption.getOrElse("unknown IP")}")
  }

  override def receiveCommand: Receive = waitingForActorSinkInit

  private[this] def waitingForActorSinkInit: Receive =
    publishStatus orElse sendKeepAlive(sendNotification = sendJsonRpcNotification) orElse {
      case ActorSinkInit =>
        sender() ! ActorSinkAck
        sendJsonRpcNotification(SupportedVersionsNotification(CompatibleVersionNumbers))
        context.become(receiveActorSinkMessages())
    }

  private[this] def receiveActorSinkMessages(
      sendResponse: (Response, Long) => Unit = sendJsonRpcResponse,
      sendNotification: Notification => Unit = sendJsonRpcNotification): Receive =
    publishStatus orElse commandReceivedConfirmation orElse sendKeepAlive(sendNotification) orElse {
      case WrappedJsonRpcRequest(jsonRpcRequestMessage) =>
        sender() ! ActorSinkAck
        keepAliveGeneratorActor ! FrameReceivedEvent
        Command.read(jsonRpcRequestMessage) match {
          case error: JsError =>
            log.warning(s"Receive error: $error")
            send(
              WrappedJsonRpcResponse(
                JsonRpcResponseErrorMessage.invalidRequest(error, jsonRpcRequestMessage.id)
              )
            )
          case JsSuccess(command, _) =>
            jsonRpcRequestMessage.id match {
              case NoCorrelationId =>
                sendJsonRpcErrorResponse(
                  error = "Correlation ID must be set",
                  correlationId = jsonRpcRequestMessage.id
                )
              case StringCorrelationId(_) =>
                sendJsonRpcErrorResponse(
                  error = "String correlation IDs are not supported",
                  correlationId = jsonRpcRequestMessage.id
                )
              case NumericCorrelationId(value) if !value.isValidLong =>
                sendJsonRpcErrorResponse(
                  error = "Floating point correlation IDs are not supported",
                  correlationId = jsonRpcRequestMessage.id
                )
              case NumericCorrelationId(value) =>
                handleCommand(command, value.toLongExact)
            }
        }
      case ZoneAlreadyExists(createZoneCommand, correlationId, sequenceNumber, deliveryId) =>
        exactlyOnce(sequenceNumber, deliveryId)(
          // asScala perhaps isn't the best name; we're just converting from the ZoneValidatorActor protocol equivalent.
          // Converting it back to the WS type is what ensures we'll then end up generating a fresh ZoneId when we then
          // convert it back again _from_ the WS type.
          handleCommand(ProtoConverter[ZoneCommand, ZoneValidatorMessage.ZoneCommand].asScala(createZoneCommand),
                        correlationId)
        )
      case ZoneResponseWithIds(response, correlationId, sequenceNumber, deliveryId) =>
        exactlyOnce(sequenceNumber, deliveryId)(
          // asScala perhaps isn't the best name; we're just converting from the ZoneValidatorActor protocol equivalent.
          sendResponse(ProtoConverter[ZoneResponse, ZoneValidatorMessage.ZoneResponse].asScala(response),
                       correlationId)
        )
      case ZoneNotificationWithIds(notification, sequenceNumber, deliveryId) =>
        exactlyOnce(sequenceNumber, deliveryId)(
          // asScala perhaps isn't the best name; we're just converting from the ZoneValidatorActor protocol equivalent.
          sendNotification(
            ProtoConverter[ZoneNotification, ZoneValidatorMessage.ZoneNotification].asScala(notification))
        )
      case ZoneRestarted(zoneId) =>
        nextExpectedMessageSequenceNumbers = nextExpectedMessageSequenceNumbers.filterKeys(validator =>
          ZoneId(UUID.fromString(validator.path.name)) != zoneId)
        commandSequenceNumbers = commandSequenceNumbers - zoneId
        pendingDeliveries(zoneId).foreach(confirmDelivery)
        pendingDeliveries = pendingDeliveries - zoneId
        sendNotification(ZoneTerminatedNotification(zoneId))
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
    case ZoneCommandReceivedConfirmation(zoneId, deliveryId) =>
      confirmDelivery(deliveryId)
      pendingDeliveries = pendingDeliveries + (zoneId -> (pendingDeliveries(zoneId) - deliveryId))
      if (pendingDeliveries(zoneId).isEmpty) {
        pendingDeliveries = pendingDeliveries - zoneId
      }
  }

  private[this] def sendKeepAlive(sendNotification: Notification => Unit): Receive = {
    case SendKeepAlive =>
      sendNotification(KeepAliveNotification)
  }

  private[this] def handleCommand(command: Command, correlationId: Long) = {
    command match {
      case zoneCommand: ZoneCommand =>
        // asProto perhaps isn't the best name; we're just converting to the ZoneValidatorActor protocol equivalent.
        val protoZoneCommand = ProtoConverter[ZoneCommand, ZoneValidatorMessage.ZoneCommand].asProto(zoneCommand)
        val zoneId           = protoZoneCommand.zoneId
        val sequenceNumber   = commandSequenceNumbers(zoneId)
        commandSequenceNumbers = commandSequenceNumbers + (zoneId -> (sequenceNumber + 1))
        deliver(zoneValidatorShardRegion.path) { deliveryId =>
          pendingDeliveries = pendingDeliveries + (zoneId -> (pendingDeliveries(zoneId) + deliveryId))
          AuthenticatedZoneCommandWithIds(
            publicKey,
            protoZoneCommand,
            correlationId,
            sequenceNumber,
            deliveryId
          )
        }
    }
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

  private[this] def sendJsonRpcNotification(notification: Notification): Unit =
    send(WrappedJsonRpcNotification(Notification.write(notification)))

  private[this] def sendJsonRpcResponse(response: Response, correlationId: Long): Unit =
    response match {
      case EmptyZoneResponse =>
        sys.error("EmptyZoneResponse")
      case ErrorResponse(error) =>
        sendJsonRpcErrorResponse(error, NumericCorrelationId(correlationId))
      case successResponse: SuccessResponse =>
        sendJsonRpcSuccessResponse(successResponse, correlationId)
    }

  private[this] def sendJsonRpcErrorResponse(error: String, correlationId: CorrelationId): Unit =
    send(
      WrappedJsonRpcResponse(
        JsonRpcResponseErrorMessage.applicationError(
          code = JsonRpcResponseErrorMessage.ReservedErrorCodeFloor - 1,
          message = error,
          data = None,
          correlationId
        )
      ))

  private[this] def sendJsonRpcSuccessResponse(response: SuccessResponse, correlationId: Long): Unit =
    send(WrappedJsonRpcResponse(SuccessResponse.write(response, NumericCorrelationId(correlationId))))

  private[this] def send(
      wrappedJsonRpcOrProtobufResponseOrNotification: WrappedJsonRpcOrProtobufResponseOrNotification): Unit = {
    upstream ! wrappedJsonRpcOrProtobufResponseOrNotification
    keepAliveGeneratorActor ! FrameSentEvent
  }
}
