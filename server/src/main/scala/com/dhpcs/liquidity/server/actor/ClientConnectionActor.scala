package com.dhpcs.liquidity.server.actor

import java.net.InetAddress
import java.util.UUID

import akka.actor._
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Publish
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.model.ws.{BinaryMessage, Message => WsMessage}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}
import akka.typed.scaladsl.adapter._
import akka.typed.{Behavior, PostStop, Terminated}
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
import com.dhpcs.liquidity.ws.protocol.ProtoBindings._
import com.dhpcs.liquidity.ws.protocol._

import scala.concurrent.duration._

object ClientConnectionActor {

  def webSocketFlow[A](behaviour: ActorRef => Behavior[A])(implicit factory: ActorRefFactory,
                                                           mat: Materializer): Flow[WsMessage, WsMessage, NotUsed] =
    InFlow
      .via(actorFlow[A](behaviour))
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

  private def actorFlow[A](behaviour: ActorRef => Behavior[A])(
      implicit factory: ActorRefFactory,
      mat: Materializer): Flow[proto.ws.protocol.ServerMessage, proto.ws.protocol.ClientMessage, NotUsed] = {
    val (outActor, publisher) = Source
      .actorRef[proto.ws.protocol.ClientMessage](bufferSize = 16, overflowStrategy = OverflowStrategy.fail)
      .toMat(Sink.asPublisher(false))(Keep.both)
      .run()
    // TODO: Switch to fromSinkAndSourceCoupled?
    Flow.fromSinkAndSource(
      Sink.actorRefWithAck(
        factory.actorOf(
          Props(new Actor {
            val flowActor: ActorRef =
              context.watch(context.spawnAnonymous(behaviour(outActor)).toUntyped)
            override def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy
            override def receive: Receive = {
              case _: Status.Success | _: Status.Failure =>
                flowActor ! PoisonPill
              case _: akka.actor.Terminated =>
                context.stop(self)
                outActor ! Status.Success(())
              case ActorSinkInit(_) =>
                flowActor ! ActorSinkInit(sender())
              case other: proto.ws.protocol.ServerMessage =>
                flowActor ! WrappedServerMessage(sender(), other)
            }
          })
        ),
        onInitMessage = ActorSinkInit(Actor.noSender),
        ackMessage = ActorSinkAck,
        onCompleteMessage = Status.Success(())
      ),
      Source.fromPublisher(publisher)
    )
  }

  private case object PublishStatusTimerKey

  private object PingGeneratorActor {

    sealed abstract class PingGeneratorMessage
    case object FrameReceivedEvent extends PingGeneratorMessage
    case object FrameSentEvent     extends PingGeneratorMessage
    case object ReceiveTimeout     extends PingGeneratorMessage

    def behaviour(pingInterval: FiniteDuration,
                  pingSender: typed.ActorRef[SendPingTick.type]): Behavior[PingGeneratorMessage] =
      typed.scaladsl.Actor.deferred { context =>
        context.setReceiveTimeout(pingInterval, ReceiveTimeout)
        typed.scaladsl.Actor.immutable[PingGeneratorMessage]((_, message) =>
          message match {
            case FrameReceivedEvent | FrameSentEvent =>
              typed.scaladsl.Actor.same

            case ReceiveTimeout =>
              pingSender ! SendPingTick
              typed.scaladsl.Actor.same
        })
      }

  }

  def behaviour(pingInterval: FiniteDuration, zoneValidatorShardRegion: ActorRef, remoteAddress: InetAddress)(
      webSocketOut: ActorRef): Behavior[ClientConnectionMessage] =
    typed.scaladsl.Actor.deferred(context =>
      typed.scaladsl.Actor.withTimers { timers =>
        val log = Logging(context.system.toUntyped, context.self.toUntyped)
        log.info(s"Starting for $remoteAddress")
        val mediator = DistributedPubSub(context.system.toUntyped).mediator
        timers.startPeriodicTimer(PublishStatusTimerKey, PublishStatusTick, 30.seconds)
        val pingGeneratorActor =
          context.spawn(PingGeneratorActor.behaviour(pingInterval, context.self), "ping-generator")
        context.watch(pingGeneratorActor)
        waitingForActorSinkInit(zoneValidatorShardRegion,
                                remoteAddress,
                                webSocketOut,
                                log,
                                mediator,
                                pingGeneratorActor)
    })

  def waitingForActorSinkInit(
      zoneValidatorShardRegion: ActorRef,
      remoteAddress: InetAddress,
      webSocketOut: ActorRef,
      log: LoggingAdapter,
      mediator: ActorRef,
      pingGeneratorActor: typed.ActorRef[PingGeneratorActor.PingGeneratorMessage]): Behavior[ClientConnectionMessage] =
    typed.scaladsl.Actor.immutable[ClientConnectionMessage]((_, message) =>
      message match {
        case ActorSinkInit(webSocketIn) =>
          webSocketIn ! ActorSinkAck
          val keyOwnershipChallenge = Authentication.createKeyOwnershipChallengeMessage()
          sendClientMessage(
            webSocketOut,
            pingGeneratorActor,
            proto.ws.protocol.ClientMessage.Message.KeyOwnershipChallenge(keyOwnershipChallenge)
          )
          waitingForKeyOwnershipProof(zoneValidatorShardRegion,
                                      remoteAddress,
                                      webSocketOut,
                                      log,
                                      mediator,
                                      pingGeneratorActor,
                                      keyOwnershipChallenge)

        case PublishStatusTick | SendPingTick =>
          typed.scaladsl.Actor.same

        case wrappedServerMessage: WrappedServerMessage =>
          log.warning(s"Stopping due to unexpected message; required ActorSinkInit but received $wrappedServerMessage")
          typed.scaladsl.Actor.stopped

        case zoneResponseEnvelope: ZoneResponseEnvelope =>
          log.warning(s"Stopping due to unexpected message; required ActorSinkInit but received $zoneResponseEnvelope")
          typed.scaladsl.Actor.stopped

        case zoneNotificationEnvelope: ZoneNotificationEnvelope =>
          log.warning(
            s"Stopping due to unexpected message; required ActorSinkInit but received $zoneNotificationEnvelope")
          typed.scaladsl.Actor.stopped
    })

  private def waitingForKeyOwnershipProof(
      zoneValidatorShardRegion: ActorRef,
      remoteAddress: InetAddress,
      webSocketOut: ActorRef,
      log: LoggingAdapter,
      mediator: ActorRef,
      pingGeneratorActor: typed.ActorRef[PingGeneratorActor.PingGeneratorMessage],
      keyOwnershipChallengeMessage: proto.ws.protocol.ClientMessage.KeyOwnershipChallenge)
    : Behavior[ClientConnectionMessage] =
    typed.scaladsl.Actor.immutable[ClientConnectionMessage] { (context, message) =>
      message match {
        case actorSinkInit: ActorSinkInit =>
          log.warning(
            s"Stopping due to unexpected message; required CompleteKeyOwnershipProof but received $actorSinkInit")
          typed.scaladsl.Actor.stopped

        case PublishStatusTick =>
          typed.scaladsl.Actor.same

        case SendPingTick =>
          sendPingCommand(webSocketOut, pingGeneratorActor)
          typed.scaladsl.Actor.same

        case WrappedServerMessage(webSocketIn, serverMessage) =>
          webSocketIn ! ActorSinkAck
          pingGeneratorActor ! PingGeneratorActor.FrameReceivedEvent
          serverMessage.message match {
            case other @ (proto.ws.protocol.ServerMessage.Message.Empty |
                _: proto.ws.protocol.ServerMessage.Message.Command |
                _: proto.ws.protocol.ServerMessage.Message.Response) =>
              log.warning(s"Stopping due to unexpected message; required CompleteKeyOwnershipProof but received $other")
              typed.scaladsl.Actor.stopped

            case proto.ws.protocol.ServerMessage.Message.KeyOwnershipProof(keyOwnershipProofMessage) =>
              val publicKey = PublicKey(keyOwnershipProofMessage.publicKey.toByteArray)
              if (!Authentication.isValidKeyOwnershipProof(keyOwnershipChallengeMessage, keyOwnershipProofMessage)) {
                log.warning(
                  "Stopping due to invalid key ownership proof for public key with fingerprint " +
                    s"${publicKey.fingerprint}.")
                typed.scaladsl.Actor.stopped
              } else {
                context.self ! PublishStatusTick
                receiveActorSinkMessages(zoneValidatorShardRegion,
                                         remoteAddress,
                                         webSocketOut,
                                         log,
                                         mediator,
                                         pingGeneratorActor,
                                         publicKey,
                                         notificationSequenceNumbers = Map.empty)
              }
          }

        case zoneResponseEnvelope: ZoneResponseEnvelope =>
          log.warning(
            "Stopping due to unexpected message; required CompleteKeyOwnershipProof but received " +
              s"$zoneResponseEnvelope")
          typed.scaladsl.Actor.stopped

        case zoneNotificationEnvelope: ZoneNotificationEnvelope =>
          log.warning(
            "Stopping due to unexpected message; required CompleteKeyOwnershipProof but received " +
              s"$zoneNotificationEnvelope")
          typed.scaladsl.Actor.stopped
      }
    }

  private def receiveActorSinkMessages(
      zoneValidatorShardRegion: ActorRef,
      remoteAddress: InetAddress,
      webSocketOut: ActorRef,
      log: LoggingAdapter,
      mediator: ActorRef,
      pingGeneratorActor: typed.ActorRef[PingGeneratorActor.PingGeneratorMessage],
      publicKey: PublicKey,
      notificationSequenceNumbers: Map[ActorRef, Long]): Behavior[ClientConnectionMessage] =
    typed.scaladsl.Actor.immutable[ClientConnectionMessage] { (context, message) =>
      message match {
        case actorSinkInit: ActorSinkInit =>
          log.warning(s"Stopping due to unexpected message; received $actorSinkInit")
          typed.scaladsl.Actor.stopped

        case PublishStatusTick =>
          mediator ! Publish(
            ClientMonitorActor.ClientStatusTopic,
            UpsertActiveClientSummary(context.self, ActiveClientSummary(publicKey))
          )
          typed.scaladsl.Actor.same

        case SendPingTick =>
          sendPingCommand(webSocketOut, pingGeneratorActor)
          typed.scaladsl.Actor.same

        case WrappedServerMessage(webSocketIn, serverMessage) =>
          webSocketIn ! ActorSinkAck
          pingGeneratorActor ! PingGeneratorActor.FrameReceivedEvent
          serverMessage.message match {
            case other @ (proto.ws.protocol.ServerMessage.Message.Empty |
                _: proto.ws.protocol.ServerMessage.Message.KeyOwnershipProof) =>
              log.warning(s"Stopping due to unexpected message; required Command or Response but received $other")
              typed.scaladsl.Actor.stopped

            case proto.ws.protocol.ServerMessage.Message.Command(protoCommand) =>
              protoCommand.command match {
                case proto.ws.protocol.ServerMessage.Command.Command.Empty =>
                  typed.scaladsl.Actor.same

                case proto.ws.protocol.ServerMessage.Command.Command.CreateZoneCommand(protoCreateZoneCommand) =>
                  val createZoneCommand =
                    ProtoBinding[CreateZoneCommand, proto.ws.protocol.ZoneCommand.CreateZoneCommand, Any]
                      .asScala(protoCreateZoneCommand)(())
                  zoneValidatorShardRegion ! ZoneCommandEnvelope(
                    zoneId = ZoneId(UUID.randomUUID.toString),
                    remoteAddress,
                    publicKey,
                    protoCommand.correlationId,
                    createZoneCommand
                  )
                  typed.scaladsl.Actor.same

                case proto.ws.protocol.ServerMessage.Command.Command.ZoneCommandEnvelope(
                    proto.ws.protocol.ServerMessage.Command.ZoneCommandEnvelope(zoneId, protoZoneCommand)
                    ) =>
                  val zoneCommand =
                    ProtoBinding[ZoneCommand, Option[proto.ws.protocol.ZoneCommand], Any].asScala(protoZoneCommand)(())
                  zoneCommand match {
                    case _: CreateZoneCommand =>
                      log.warning(s"Stopping due to receipt of illegally enveloped CreateZoneCommand")
                      typed.scaladsl.Actor.stopped

                    case _ =>
                      zoneValidatorShardRegion ! ZoneCommandEnvelope(
                        zoneId = ZoneId(zoneId),
                        remoteAddress,
                        publicKey,
                        protoCommand.correlationId,
                        zoneCommand
                      )
                      typed.scaladsl.Actor.same
                  }
              }

            case proto.ws.protocol.ServerMessage.Message.Response(protoResponse) =>
              protoResponse.response match {
                case proto.ws.protocol.ServerMessage.Response.Response.Empty =>
                  log.warning("Stopping due to unexpected message; required PingResponse but received Empty")
                  typed.scaladsl.Actor.stopped

                case proto.ws.protocol.ServerMessage.Response.Response.PingResponse(_) =>
                  typed.scaladsl.Actor.same
              }
          }

        case ZoneResponseEnvelope(zoneValidator, correlationId, zoneResponse) =>
          sendClientMessage(
            webSocketOut,
            pingGeneratorActor,
            proto.ws.protocol.ClientMessage.Message.Response(proto.ws.protocol.ClientMessage.Response(
              correlationId,
              proto.ws.protocol.ClientMessage.Response.Response.ZoneResponse(
                ProtoBinding[ZoneResponse, proto.ws.protocol.ZoneResponse, Any].asProto(zoneResponse)
              )
            ))
          )
          zoneResponse match {
            case JoinZoneResponse(Valid(_)) =>
              context.watch(zoneValidator)
              receiveActorSinkMessages(zoneValidatorShardRegion,
                                       remoteAddress,
                                       webSocketOut,
                                       log,
                                       mediator,
                                       pingGeneratorActor,
                                       publicKey,
                                       notificationSequenceNumbers + (zoneValidator -> 0))

            case QuitZoneResponse(Valid(_)) =>
              context.unwatch(zoneValidator)
              receiveActorSinkMessages(zoneValidatorShardRegion,
                                       remoteAddress,
                                       webSocketOut,
                                       log,
                                       mediator,
                                       pingGeneratorActor,
                                       publicKey,
                                       notificationSequenceNumbers - zoneValidator)

            case _ =>
              typed.scaladsl.Actor.same
          }

        case ZoneNotificationEnvelope(zoneValidator, zoneId, sequenceNumber, zoneNotification) =>
          notificationSequenceNumbers.get(zoneValidator) match {
            case None =>
              log.warning(
                "Stopping due to unexpected notification (JoinZoneCommand sent but no JoinZoneResponse received)")
              typed.scaladsl.Actor.stopped

            case Some(expectedSequenceNumber) =>
              if (sequenceNumber != expectedSequenceNumber) {
                log.warning(s"Stopping due to unexpected notification ($sequenceNumber != $expectedSequenceNumber)")
                typed.scaladsl.Actor.stopped
              } else {
                sendClientMessage(
                  webSocketOut,
                  pingGeneratorActor,
                  proto.ws.protocol.ClientMessage.Message.Notification(
                    proto.ws.protocol.ClientMessage.Notification(
                      proto.ws.protocol.ClientMessage.Notification.Notification
                        .ZoneNotificationEnvelope(
                          proto.ws.protocol.ClientMessage.Notification.ZoneNotificationEnvelope(
                            zoneId.id.toString,
                            Some(ProtoBinding[ZoneNotification, proto.ws.protocol.ZoneNotification, Any]
                              .asProto(zoneNotification))
                          ))
                    ))
                )
                val nextExpectedSequenceNumber = expectedSequenceNumber + 1
                receiveActorSinkMessages(
                  zoneValidatorShardRegion,
                  remoteAddress,
                  webSocketOut,
                  log,
                  mediator,
                  pingGeneratorActor,
                  publicKey,
                  notificationSequenceNumbers + (zoneValidator -> nextExpectedSequenceNumber)
                )
                typed.scaladsl.Actor.same
              }
          }
      }
    } onSignal {
      case (_, PostStop) =>
        log.info(s"Stopped for $remoteAddress")
        typed.scaladsl.Actor.same

      case (_, Terminated(zoneValidator)) =>
        log.warning(s"Stopping due to termination of joined zone $zoneValidator")
        typed.scaladsl.Actor.stopped
    }

  private def sendPingCommand(webSocketOut: ActorRef,
                              pingGeneratorActor: typed.ActorRef[PingGeneratorActor.PingGeneratorMessage]): Unit =
    sendClientMessage(
      webSocketOut,
      pingGeneratorActor,
      proto.ws.protocol.ClientMessage.Message.Command(
        proto.ws.protocol.ClientMessage.Command(
          correlationId = -1,
          command = proto.ws.protocol.ClientMessage.Command.Command.PingCommand(com.google.protobuf.ByteString.EMPTY)
        ))
    )

  private def sendClientMessage(webSocketOut: ActorRef,
                                pingGeneratorActor: typed.ActorRef[PingGeneratorActor.PingGeneratorMessage],
                                clientMessage: proto.ws.protocol.ClientMessage.Message): Unit = {
    webSocketOut ! proto.ws.protocol.ClientMessage(clientMessage)
    pingGeneratorActor ! PingGeneratorActor.FrameSentEvent
  }
}
