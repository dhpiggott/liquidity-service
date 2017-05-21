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

  def props(ip: RemoteAddress, publicKey: PublicKey, zoneValidatorShardRegion: ActorRef, pingInterval: FiniteDuration)(
      upstream: ActorRef): Props =
    Props(
      new ClientConnectionActor(
        ip,
        publicKey,
        zoneValidatorShardRegion,
        pingInterval,
        upstream
      )
    )

  def webSocketFlow(props: ActorRef => Props, name: String)(implicit factory: ActorRefFactory,
                                                            mat: Materializer): Flow[WsMessage, WsMessage, NotUsed] =
    InFlow
      .via(actorFlow[proto.ws.protocol.ServerMessage, proto.ws.protocol.ClientMessage](props, name))
      .via(OutFlow)

  private final val InFlow: Flow[WsMessage, proto.ws.protocol.ServerMessage, NotUsed] =
    Flow[WsMessage].flatMapConcat(wsMessage =>
      for (byteString <- wsMessage.asBinaryMessage match {
             case BinaryMessage.Streamed(dataStream) => dataStream.fold(ByteString.empty)((acc, data) => acc ++ data)
             case BinaryMessage.Strict(data)         => Source.single(data)
           }) yield proto.ws.protocol.ServerMessage.parseFrom(byteString.toArray))

  private final val OutFlow: Flow[proto.ws.protocol.ClientMessage, WsMessage, NotUsed] =
    Flow[proto.ws.protocol.ClientMessage].map(
      serverMessage => BinaryMessage(ByteString(serverMessage.toByteArray))
    )

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
            val flowActor: ActorRef =
              context.watch(context.actorOf(props(outActor).withDeploy(Deploy.local), name))
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

  case object ActorSinkInit
  case object ActorSinkAck

  private case object PublishStatus

  private object PingGeneratorActor {

    def props(pingInterval: FiniteDuration): Props = Props(new PingGeneratorActor(pingInterval))

    case object FrameReceivedEvent
    case object FrameSentEvent
    case object SendPingCommand

  }

  private class PingGeneratorActor(pingInterval: FiniteDuration) extends Actor {

    import com.dhpcs.liquidity.server.actor.ClientConnectionActor.PingGeneratorActor._

    context.setReceiveTimeout(pingInterval)

    override def receive: Receive = {
      case ReceiveTimeout                      => context.parent ! SendPingCommand
      case FrameReceivedEvent | FrameSentEvent => ()
    }
  }
}

class ClientConnectionActor(ip: RemoteAddress,
                            publicKey: PublicKey,
                            zoneValidatorShardRegion: ActorRef,
                            pingInterval: FiniteDuration,
                            upstream: ActorRef)
    extends PersistentActor
    with ActorLogging
    with AtLeastOnceDelivery {

  import com.dhpcs.liquidity.server.actor.ClientConnectionActor.PingGeneratorActor._
  import context.dispatcher

  private[this] val mediator          = DistributedPubSub(context.system).mediator
  private[this] val publishStatusTick = context.system.scheduler.schedule(0.seconds, 30.seconds, self, PublishStatus)
  private[this] val pingGeneratorActor =
    context.actorOf(PingGeneratorActor.props(pingInterval).withDeploy(Deploy.local))

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
    publishStatus orElse sendPingCommand orElse {
      case ActorSinkInit =>
        sender() ! ActorSinkAck
        context.become(receiveActorSinkMessages)
    }

  private[this] def receiveActorSinkMessages: Receive =
    publishStatus orElse commandReceivedConfirmation orElse sendPingCommand orElse {
      case serverMessage: proto.ws.protocol.ServerMessage =>
        sender() ! ActorSinkAck
        pingGeneratorActor ! FrameReceivedEvent
        serverMessage.commandOrResponse match {
          case proto.ws.protocol.ServerMessage.CommandOrResponse.Empty =>
            sys.error("Empty")
          case proto.ws.protocol.ServerMessage.CommandOrResponse.Command(protoCommand) =>
            protoCommand.command match {
              case proto.ws.protocol.ServerMessage.Command.Command.Empty =>
                sys.error("Empty")
              case proto.ws.protocol.ServerMessage.Command.Command.ZoneCommand(protoZoneCommand) =>
                val zoneCommand = ProtoConverter[ZoneCommand, proto.ws.protocol.ZoneCommand.ZoneCommand]
                  .asScala(protoZoneCommand.zoneCommand)
                handleZoneCommand(zoneCommand, protoCommand.correlationId)
            }
          case proto.ws.protocol.ServerMessage.CommandOrResponse.Response(protoResponse) =>
            protoResponse.response match {
              case proto.ws.protocol.ServerMessage.Response.Response.Empty =>
                sys.error("Empty")
              case proto.ws.protocol.ServerMessage.Response.Response.PingResponse(_) =>
            }
        }
        context.become(receiveActorSinkMessages)
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
          sendServerResponse(ProtoConverter[ZoneResponse, ZoneValidatorMessage.ZoneResponse].asScala(response),
                             correlationId)
        )
      case ZoneNotificationWithIds(notification, sequenceNumber, deliveryId) =>
        exactlyOnce(sequenceNumber, deliveryId)(
          // asScala perhaps isn't the best name; we're just converting from the ZoneValidatorActor protocol equivalent.
          sendClientNotification(
            ProtoConverter[ZoneNotification, ZoneValidatorMessage.ZoneNotification].asScala(notification))
        )
      case ZoneRestarted(zoneId) =>
        nextExpectedMessageSequenceNumbers = nextExpectedMessageSequenceNumbers.filterKeys(validator =>
          ZoneId(UUID.fromString(validator.path.name)) != zoneId)
        commandSequenceNumbers = commandSequenceNumbers - zoneId
        pendingDeliveries(zoneId).foreach(confirmDelivery)
        pendingDeliveries = pendingDeliveries - zoneId
        sendClientNotification(ZoneTerminatedNotification(zoneId))
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

  private[this] def sendPingCommand: Receive = {
    case SendPingCommand => sendClientCommand(PingCommand, correlationId = -1)
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

  private[this] def sendClientCommand(clientCommand: ClientCommand, correlationId: Long): Unit =
    sendClientMessage(
      proto.ws.protocol.ClientMessage(
        proto.ws.protocol.ClientMessage.CommandOrResponseOrNotification.Command(
          proto.ws.protocol.ClientMessage.Command(
            correlationId,
            clientCommand match {
              case PingCommand =>
                proto.ws.protocol.ClientMessage.Command.Command.PingCommand(
                  proto.ws.protocol.PingCommand()
                )
            }
          )
        )
      )
    )

  private[this] def sendClientNotification(clientNotification: ClientNotification): Unit =
    sendClientMessage(
      proto.ws.protocol.ClientMessage(
        proto.ws.protocol.ClientMessage.CommandOrResponseOrNotification.Notification(
          proto.ws.protocol.ClientMessage.Notification(
            clientNotification match {
              case zoneNotification: ZoneNotification =>
                proto.ws.protocol.ClientMessage.Notification.Notification.ZoneNotification(
                  proto.ws.protocol.ZoneNotification(
                    ProtoConverter[ZoneNotification, proto.ws.protocol.ZoneNotification.ZoneNotification]
                      .asProto(zoneNotification)
                  )
                )

            }
          )
        )
      )
    )

  private[this] def sendServerResponse(serverResponse: ServerResponse, correlationId: Long): Unit =
    sendClientMessage(
      proto.ws.protocol.ClientMessage(
        proto.ws.protocol.ClientMessage.CommandOrResponseOrNotification.Response(
          proto.ws.protocol.ClientMessage.Response(
            correlationId,
            serverResponse match {
              case zoneResponse: ZoneResponse =>
                proto.ws.protocol.ClientMessage.Response.Response.ZoneResponse(
                  proto.ws.protocol.ZoneResponse(
                    ProtoConverter[ZoneResponse, proto.ws.protocol.ZoneResponse.ZoneResponse]
                      .asProto(zoneResponse)
                  )
                )
            }
          )
        )
      )
    )

  private[this] def sendClientMessage(clientMessage: proto.ws.protocol.ClientMessage): Unit = {
    upstream ! clientMessage
    pingGeneratorActor ! FrameSentEvent
  }
}
