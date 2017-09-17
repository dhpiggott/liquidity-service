package com.dhpcs.liquidity.server.actor

import java.net.InetAddress

import akka.actor._
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Publish
import akka.http.scaladsl.model.ws.{BinaryMessage, Message => WsMessage}
import akka.persistence.{AtLeastOnceDelivery, PersistentActor}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}
import akka.typed.Behavior
import akka.typed.scaladsl.adapter._
import akka.util.ByteString
import akka.{NotUsed, typed}
import cats.data.Validated.Valid
import com.dhpcs.liquidity.actor.protocol.clientconnection._
import com.dhpcs.liquidity.actor.protocol.clientmonitor._
import com.dhpcs.liquidity.actor.protocol.zonevalidator._
import com.dhpcs.liquidity.model.ProtoBindings._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.server.actor.ClientConnectionActor._
import com.dhpcs.liquidity.ws.protocol.ProtoBindings._
import com.dhpcs.liquidity.ws.protocol._

import scala.concurrent.duration._

object ClientConnectionActor {

  def props(remoteAddress: InetAddress, zoneValidatorShardRegion: ActorRef, pingInterval: FiniteDuration)(
      upstream: ActorRef): Props =
    Props(
      new ClientConnectionActor(
        remoteAddress,
        zoneValidatorShardRegion,
        pingInterval,
        upstream
      )
    )

  def webSocketFlow(props: ActorRef => Props)(implicit factory: ActorRefFactory,
                                              mat: Materializer): Flow[WsMessage, WsMessage, NotUsed] =
    InFlow
      .via(actorFlow[proto.ws.protocol.ServerMessage, proto.ws.protocol.ClientMessage](props))
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

  private def actorFlow[In, Out](props: ActorRef => Props)(implicit factory: ActorRefFactory,
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
              context.watch(context.actorOf(props(outActor)))
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
          })
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

  private case object PublishStatusTimerKey
  private case object PublishStatus

  private object PingGeneratorActor {

    sealed abstract class PingGeneratorMessage
    case object FrameReceivedEvent extends PingGeneratorMessage
    case object FrameSentEvent     extends PingGeneratorMessage
    case object SendPingCommand    extends PingGeneratorMessage

    def behaviour(pingInterval: FiniteDuration, parent: ActorRef): Behavior[PingGeneratorMessage] =
      typed.scaladsl.Actor.deferred { context =>
        context.setReceiveTimeout(pingInterval, SendPingCommand)
        typed.scaladsl.Actor.immutable[PingGeneratorMessage] { (_, message) =>
          message match {
            case FrameReceivedEvent | FrameSentEvent => ()
            case SendPingCommand                     => parent ! SendPingCommand
          }
          typed.scaladsl.Actor.same
        }
      }
  }
}

class ClientConnectionActor(remoteAddress: InetAddress,
                            zoneValidatorShardRegion: ActorRef,
                            pingInterval: FiniteDuration,
                            upstream: ActorRef)
    extends PersistentActor
    with ActorLogging
    with AtLeastOnceDelivery
    with Timers {

  import com.dhpcs.liquidity.server.actor.ClientConnectionActor.PingGeneratorActor._

  private[this] val mediator = DistributedPubSub(context.system).mediator
  private[this] val pingGeneratorActor = context.spawn(
    akka.typed.scaladsl.Actor
      .supervise(PingGeneratorActor.behaviour(pingInterval, self))
      .onFailure[Exception](akka.typed.SupervisorStrategy.restart),
    "ping-generator"
  )

  private[this] var nextExpectedMessageSequenceNumbers = Map.empty[ActorRef, Long].withDefaultValue(1L)
  private[this] var commandSequenceNumbers             = Map.empty[ZoneId, Long].withDefaultValue(1L)
  private[this] var pendingDeliveries                  = Map.empty[ZoneId, Set[Long]].withDefaultValue(Set.empty)

  timers.startPeriodicTimer(PublishStatusTimerKey, PublishStatus, 30.seconds)

  override def persistenceId: String = self.path.name

  override def preStart(): Unit = {
    super.preStart()
    log.info(s"Started for $remoteAddress")
  }

  override def postStop(): Unit = {
    super.postStop()
    log.info(s"Stopped for $remoteAddress")
  }

  override def receiveCommand: Receive = waitingForActorSinkInit

  private[this] def waitingForActorSinkInit: Receive =
    publishStatus(maybePublicKey = None) orElse sendPingCommand orElse {
      case ActorSinkInit =>
        sender() ! ActorSinkAck
        val keyOwnershipChallenge = Authentication.createKeyOwnershipChallengeMessage()
        sendClientMessage(proto.ws.protocol.ClientMessage.Message.KeyOwnershipChallenge(keyOwnershipChallenge))
        context.become(waitingForKeyOwnershipProof(keyOwnershipChallenge))
    }

  private[this] def waitingForKeyOwnershipProof(
      keyOwnershipChallengeMessage: proto.ws.protocol.ClientMessage.KeyOwnershipChallenge): Receive =
    publishStatus(maybePublicKey = None) orElse sendPingCommand orElse {
      case serverMessage: proto.ws.protocol.ServerMessage =>
        sender() ! ActorSinkAck
        pingGeneratorActor ! FrameReceivedEvent
        serverMessage.message match {
          case other @ (proto.ws.protocol.ServerMessage.Message.Empty |
              _: proto.ws.protocol.ServerMessage.Message.Command |
              _: proto.ws.protocol.ServerMessage.Message.Response) =>
            log.warning(s"Stopping due to unexpected message; required CompleteKeyOwnershipProof but received $other")
            context.stop(self)
          case proto.ws.protocol.ServerMessage.Message.KeyOwnershipProof(keyOwnershipProofMessage) =>
            val publicKey = PublicKey(keyOwnershipProofMessage.publicKey.toByteArray)
            if (!Authentication.isValidKeyOwnershipProof(keyOwnershipChallengeMessage, keyOwnershipProofMessage)) {
              log.warning(
                s"Stopping due to invalid key ownership proof for public key with fingerprint " +
                  s"${publicKey.fingerprint}.")
              context.stop(self)
            } else {
              context.become(receiveActorSinkMessages(publicKey))
              self ! PublishStatus
            }
        }
    }

  private[this] def receiveActorSinkMessages(publicKey: PublicKey): Receive =
    publishStatus(maybePublicKey = Some(publicKey)) orElse sendPingCommand orElse {
      case ZoneCommandReceivedConfirmation(zoneId, deliveryId) =>
        confirmDelivery(deliveryId)
        pendingDeliveries = pendingDeliveries + (zoneId -> (pendingDeliveries(zoneId) - deliveryId))
        if (pendingDeliveries(zoneId).isEmpty) {
          pendingDeliveries = pendingDeliveries - zoneId
        }

      case serverMessage: proto.ws.protocol.ServerMessage =>
        sender() ! ActorSinkAck
        pingGeneratorActor ! FrameReceivedEvent
        serverMessage.message match {
          case other @ (proto.ws.protocol.ServerMessage.Message.Empty |
              _: proto.ws.protocol.ServerMessage.Message.KeyOwnershipProof) =>
            log.warning(s"Stopping due to unexpected message; required Command or Response but received $other")
            context.stop(self)
          case proto.ws.protocol.ServerMessage.Message.Command(protoCommand) =>
            protoCommand.command match {
              case proto.ws.protocol.ServerMessage.Command.Command.Empty =>
              case proto.ws.protocol.ServerMessage.Command.Command.CreateZoneCommand(protoCreateZoneCommand) =>
                val createZoneCommand =
                  ProtoBinding[CreateZoneCommand, proto.ws.protocol.ZoneCommand.CreateZoneCommand, Any]
                    .asScala(protoCreateZoneCommand)(())
                handleZoneCommand(
                  zoneId = ZoneId.generate,
                  publicKey,
                  protoCommand.correlationId,
                  createZoneCommand
                )
              case proto.ws.protocol.ServerMessage.Command.Command.ZoneCommandEnvelope(
                  proto.ws.protocol.ServerMessage.Command.ZoneCommandEnvelope(zoneId, protoZoneCommand)
                  ) =>
                val zoneCommand =
                  ProtoBinding[ZoneCommand, Option[proto.ws.protocol.ZoneCommand], Any].asScala(protoZoneCommand)(())
                zoneCommand match {
                  case _: CreateZoneCommand =>
                    log.warning(s"Stopping due to receipt of illegally enveloped CreateZoneCommand")
                    context.stop(self)
                  case _ =>
                    handleZoneCommand(
                      zoneId = ZoneId(zoneId),
                      publicKey,
                      protoCommand.correlationId,
                      zoneCommand
                    )
                }
            }
          case proto.ws.protocol.ServerMessage.Message.Response(protoResponse) =>
            protoResponse.response match {
              case proto.ws.protocol.ServerMessage.Response.Response.Empty =>
                log.warning("Stopping due to unexpected message; required PingResponse but received Empty")
                context.stop(self)
              case proto.ws.protocol.ServerMessage.Response.Response.PingResponse(_) =>
            }
        }
      case ZoneResponseEnvelope(correlationId, sequenceNumber, deliveryId, zoneResponse) =>
        exactlyOnce(sequenceNumber, deliveryId) {
          zoneResponse match {
            case JoinZoneResponse(Valid(_)) => context.watch(sender())
            case QuitZoneResponse(Valid(_)) => context.unwatch(sender())
            case _                          => ()
          }
          sendClientMessage(
            proto.ws.protocol.ClientMessage.Message.Response(proto.ws.protocol.ClientMessage.Response(
              correlationId,
              proto.ws.protocol.ClientMessage.Response.Response.ZoneResponse(
                ProtoBinding[ZoneResponse, proto.ws.protocol.ZoneResponse, Any].asProto(zoneResponse)
              )
            )))
        }
      case ZoneNotificationEnvelope(zoneId, sequenceNumber, deliveryId, zoneNotification) =>
        exactlyOnce(sequenceNumber, deliveryId)(
          sendClientMessage(
            proto.ws.protocol.ClientMessage.Message.Notification(
              proto.ws.protocol.ClientMessage.Notification(
                proto.ws.protocol.ClientMessage.Notification.Notification
                  .ZoneNotificationEnvelope(
                    proto.ws.protocol.ClientMessage.Notification.ZoneNotificationEnvelope(
                      zoneId.id.toString,
                      Some(ProtoBinding[ZoneNotification, proto.ws.protocol.ZoneNotification, Any]
                        .asProto(zoneNotification))
                    ))
              )))
        )
      case Terminated(_) =>
        context.stop(self)
    }

  override def receiveRecover: Receive = Actor.emptyBehavior

  private[this] def publishStatus(maybePublicKey: Option[PublicKey]): Receive = {
    case PublishStatus =>
      for (publicKey <- maybePublicKey)
        mediator ! Publish(
          ClientMonitorActor.ClientStatusTopic,
          UpsertActiveClientSummary(self, ActiveClientSummary(publicKey))
        )
  }

  private[this] def sendPingCommand: Receive = {
    case SendPingCommand =>
      sendClientMessage(
        proto.ws.protocol.ClientMessage.Message.Command(
          proto.ws.protocol.ClientMessage.Command(
            correlationId = -1,
            command = proto.ws.protocol.ClientMessage.Command.Command.PingCommand(com.google.protobuf.ByteString.EMPTY)
          )))
  }

  private[this] def handleZoneCommand(zoneId: ZoneId,
                                      publicKey: PublicKey,
                                      correlationId: Long,
                                      zoneCommand: ZoneCommand): Unit = {
    val sequenceNumber = commandSequenceNumbers(zoneId)
    commandSequenceNumbers = commandSequenceNumbers + (zoneId -> (sequenceNumber + 1))
    deliver(zoneValidatorShardRegion.path) { deliveryId =>
      pendingDeliveries = pendingDeliveries + (zoneId -> (pendingDeliveries(zoneId) + deliveryId))
      ZoneCommandEnvelope(
        zoneId,
        remoteAddress,
        publicKey,
        correlationId,
        sequenceNumber,
        deliveryId,
        zoneCommand
      )
    }
  }

  private[this] def exactlyOnce(sequenceNumber: Long, deliveryId: Long)(body: => Unit): Unit = {
    val nextExpectedMessageSequenceNumber = nextExpectedMessageSequenceNumbers(sender())
    if (sequenceNumber <= nextExpectedMessageSequenceNumber)
      sender() ! MessageReceivedConfirmation(deliveryId)
    if (sequenceNumber == nextExpectedMessageSequenceNumber) {
      nextExpectedMessageSequenceNumbers = nextExpectedMessageSequenceNumbers + (sender() -> (sequenceNumber + 1))
      body
    }
  }

  private[this] def sendClientMessage(clientMessage: proto.ws.protocol.ClientMessage.Message): Unit = {
    upstream ! proto.ws.protocol.ClientMessage(clientMessage)
    pingGeneratorActor ! FrameSentEvent
  }
}
