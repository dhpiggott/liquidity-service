package com.dhpcs.liquidity.server.actor

import java.util.UUID

import akka.NotUsed
import akka.actor._
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Publish
import akka.http.scaladsl.model.RemoteAddress
import akka.http.scaladsl.model.ws.{BinaryMessage, Message => WsMessage}
import akka.persistence.{AtLeastOnceDelivery, PersistentActor}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}
import akka.util.ByteString
import com.dhpcs.liquidity.actor.protocol._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.persistence.RichPublicKey
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.serialization.ProtoConverter
import com.dhpcs.liquidity.server.actor.ClientConnectionActor._
import com.dhpcs.liquidity.ws.protocol._

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
      .via(actorFlow[WrappedProtobufCommand, WrappedProtobufResponseOrNotification](props, name))
      .via(OutFlow)

  private final val InFlow: Flow[WsMessage, WrappedProtobufCommand, NotUsed] =
    Flow[WsMessage]
      .flatMapConcat(wsMessage =>
        for (byteString <- wsMessage.asBinaryMessage match {
               case BinaryMessage.Streamed(dataStream) => dataStream.fold(ByteString.empty)((acc, data) => acc ++ data)
               case BinaryMessage.Strict(data)         => Source.single(data)
             }) yield WrappedProtobufCommand(proto.ws.protocol.Command.parseFrom(byteString.toArray)))

  private final val OutFlow: Flow[WrappedProtobufResponseOrNotification, WsMessage, NotUsed] =
    Flow[WrappedProtobufResponseOrNotification].map {
      case WrappedProtobufResponse(protobufResponse) =>
        BinaryMessage(
          ByteString(
            proto.ws.protocol
              .ResponseOrNotification(
                proto.ws.protocol.ResponseOrNotification.ResponseOrNotification.Response(
                  protobufResponse
                )
              )
              .toByteArray))
      case WrappedProtobufNotification(protobufNotification) =>
        BinaryMessage(
          ByteString(
            proto.ws.protocol
              .ResponseOrNotification(
                proto.ws.protocol.ResponseOrNotification.ResponseOrNotification.Notification(
                  protobufNotification
                )
              )
              .toByteArray))
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

  final case class WrappedProtobufCommand(protobufCommand: proto.ws.protocol.Command)
      extends NoSerializationVerificationNeeded

  sealed abstract class WrappedProtobufResponseOrNotification extends NoSerializationVerificationNeeded
  final case class WrappedProtobufResponse(protobufResponse: proto.ws.protocol.ResponseOrNotification.Response)
      extends WrappedProtobufResponseOrNotification
  final case class WrappedProtobufNotification(
      protobufNotification: proto.ws.protocol.ResponseOrNotification.Notification)
      extends WrappedProtobufResponseOrNotification

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
    publishStatus orElse sendKeepAlive(sendNotification = sendProtobufNotification) orElse {
      case ActorSinkInit =>
        sender() ! ActorSinkAck
        context.become(receiveActorSinkMessages())
    }

  private[this] def receiveActorSinkMessages(
      sendResponse: (Response, Long) => Unit = sendProtobufResponse,
      sendNotification: Notification => Unit = sendProtobufNotification): Receive =
    publishStatus orElse commandReceivedConfirmation orElse sendKeepAlive(sendNotification) orElse {
      case WrappedProtobufCommand(protobufCommand) =>
        sender() ! ActorSinkAck
        keepAliveGeneratorActor ! FrameReceivedEvent
        // TODO: DRY
        protobufCommand.command match {
          case proto.ws.protocol.Command.Command.Empty =>
            sys.error("Empty")
          case proto.ws.protocol.Command.Command.ZoneCommand(protobufZoneCommand) =>
            val zoneCommand = ProtoConverter[ZoneCommand, proto.ws.protocol.ZoneCommand.ZoneCommand]
              .asScala(protobufZoneCommand.zoneCommand)
            handleZoneCommand(zoneCommand, protobufCommand.correlationId)
        }
        context.become(
          receiveActorSinkMessages(
            sendResponse = sendProtobufResponse,
            sendNotification = sendProtobufNotification
          )
        )
      case ZoneAlreadyExists(createZoneCommand, correlationId, sequenceNumber, deliveryId) =>
        exactlyOnce(sequenceNumber, deliveryId)(
          // asScala perhaps isn't the best name; we're just converting from the ZoneValidatorActor protocol equivalent.
          // Converting it back to the WS type is what ensures we'll then end up generating a fresh ZoneId when we then
          // convert it back again _from_ the WS type.
          handleZoneCommand(ProtoConverter[ZoneCommand, ZoneValidatorMessage.ZoneCommand].asScala(createZoneCommand),
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
        ClientStatusTopic,
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

  private[this] def handleZoneCommand(zoneCommand: ZoneCommand, correlationId: Long) = {
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

  // TODO: DRY
  private[this] def sendProtobufNotification(notification: Notification): Unit =
    send(
      WrappedProtobufNotification(
        proto.ws.protocol.ResponseOrNotification.Notification(notification match {
          case KeepAliveNotification =>
            proto.ws.protocol.ResponseOrNotification.Notification.Notification.KeepAliveNotification(
              proto.ws.protocol.KeepAliveNotification()
            )
          case zoneNotification: ZoneNotification =>
            proto.ws.protocol.ResponseOrNotification.Notification.Notification.ZoneNotification(
              proto.ws.protocol.ZoneNotification(
                ProtoConverter[ZoneNotification, proto.ws.protocol.ZoneNotification.ZoneNotification]
                  .asProto(zoneNotification))
            )
        })
      )
    )

  // TODO: DRY
  private[this] def sendProtobufResponse(response: Response, correlationId: Long): Unit =
    send(
      WrappedProtobufResponse(
        proto.ws.protocol.ResponseOrNotification.Response(
          correlationId,
          response match {
            case zoneResponse: ZoneResponse =>
              proto.ws.protocol.ResponseOrNotification.Response.Response.ZoneResponse(
                proto.ws.protocol.ZoneResponse(
                  ProtoConverter[ZoneResponse, proto.ws.protocol.ZoneResponse.ZoneResponse]
                    .asProto(zoneResponse))
              )
          }
        )
      )
    )

  private[this] def send(wrappedProtobufResponseOrNotification: WrappedProtobufResponseOrNotification): Unit = {
    upstream ! wrappedProtobufResponseOrNotification
    keepAliveGeneratorActor ! FrameSentEvent
  }
}
