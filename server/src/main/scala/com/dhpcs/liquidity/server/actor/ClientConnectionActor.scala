package com.dhpcs.liquidity.server.actor

import java.net.InetAddress
import java.util.UUID

import akka.actor.{ActorRefFactory, Props}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Publish
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.model.ws.{BinaryMessage, Message => WsMessage}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.adapter._
import akka.typed.{ActorRef, Behavior, PostStop, Terminated}
import akka.util.ByteString
import akka.{NotUsed, typed}
import cats.data.Validated.Valid
import com.dhpcs.liquidity.actor.protocol.clientconnection._
import com.dhpcs.liquidity.actor.protocol.clientmonitor._
import com.dhpcs.liquidity.actor.protocol.zonevalidator._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.ws.protocol.ProtoBindings._
import com.dhpcs.liquidity.ws.protocol._

import scala.concurrent.duration._

object ClientConnectionActor {

  def webSocketFlow[A](behavior: ActorRef[proto.ws.protocol.ClientMessage] => Behavior[A])(
      implicit factory: ActorRefFactory,
      mat: Materializer): Flow[WsMessage, WsMessage, NotUsed] =
    InFlow
      .via(actorFlow[A](behavior))
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

  private case object StopActorSink

  private def actorFlow[A](behavior: ActorRef[proto.ws.protocol.ClientMessage] => Behavior[A])(
      implicit factory: ActorRefFactory,
      mat: Materializer): Flow[proto.ws.protocol.ServerMessage, proto.ws.protocol.ClientMessage, NotUsed] = {
    val (outActor, publisher) = Source
      .actorRef[proto.ws.protocol.ClientMessage](bufferSize = 16, overflowStrategy = OverflowStrategy.fail)
      .toMat(Sink.asPublisher(false))(Keep.both)
      .run()
    Flow.fromSinkAndSourceCoupled(
      Sink.actorRefWithAck(
        factory.actorOf(Props(new akka.actor.Actor {

          private[this] val flow = context.watch(context.spawnAnonymous(behavior(outActor)).toUntyped)

          override def receive: Receive = {
            case InitActorSink(_) =>
              flow ! InitActorSink(sender())

            case serverMessage: proto.ws.protocol.ServerMessage =>
              flow ! ActorFlowServerMessage(sender(), serverMessage)

            case StopActorSink =>
              context.stop(self)
          }
        })),
        onInitMessage = InitActorSink(akka.actor.Actor.noSender),
        ackMessage = ActorSinkAck,
        onCompleteMessage = StopActorSink,
        onFailureMessage = _ => StopActorSink
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

    def behavior(pingInterval: FiniteDuration,
                 clientConnection: typed.ActorRef[ClientConnectionMessage]): Behavior[PingGeneratorMessage] =
      Actor.deferred { context =>
        context.setReceiveTimeout(pingInterval, ReceiveTimeout)
        Actor.immutable[PingGeneratorMessage]((_, message) =>
          message match {
            case FrameReceivedEvent | FrameSentEvent =>
              Actor.same

            case ReceiveTimeout =>
              clientConnection ! SendPingTick
              Actor.same
        })
      }

  }

  def behavior(pingInterval: FiniteDuration,
               zoneValidatorShardRegion: typed.ActorRef[SerializableZoneValidatorMessage],
               remoteAddress: InetAddress)(
      webSocketOut: ActorRef[proto.ws.protocol.ClientMessage]): Behavior[ClientConnectionMessage] =
    Actor.deferred(context =>
      Actor.withTimers { timers =>
        val log = Logging(context.system.toUntyped, context.self.toUntyped)
        log.info(s"Starting for $remoteAddress")
        val mediator = DistributedPubSub(context.system.toUntyped).mediator
        timers.startPeriodicTimer(PublishStatusTimerKey, PublishClientStatusTick, 30.seconds)
        val pingGenerator = context.spawn(PingGeneratorActor.behavior(pingInterval, context.self), "pingGenerator")
        waitingForActorSinkInit(zoneValidatorShardRegion, remoteAddress, webSocketOut, log, mediator, pingGenerator)
    })

  def waitingForActorSinkInit(
      zoneValidatorShardRegion: typed.ActorRef[SerializableZoneValidatorMessage],
      remoteAddress: InetAddress,
      webSocketOut: ActorRef[proto.ws.protocol.ClientMessage],
      log: LoggingAdapter,
      mediator: ActorRef[Publish],
      pingGeneratorActor: typed.ActorRef[PingGeneratorActor.PingGeneratorMessage]): Behavior[ClientConnectionMessage] =
    Actor.immutable[ClientConnectionMessage]((_, message) =>
      message match {
        case InitActorSink(webSocketIn) =>
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

        case PublishClientStatusTick | SendPingTick =>
          Actor.same

        case wrappedServerMessage: ActorFlowServerMessage =>
          log.warning(s"Stopping due to unexpected message; required ActorSinkInit but received $wrappedServerMessage")
          Actor.stopped

        case zoneResponseEnvelope: ZoneResponseEnvelope =>
          log.warning(s"Stopping due to unexpected message; required ActorSinkInit but received $zoneResponseEnvelope")
          Actor.stopped

        case zoneNotificationEnvelope: ZoneNotificationEnvelope =>
          log.warning(
            s"Stopping due to unexpected message; required ActorSinkInit but received $zoneNotificationEnvelope")
          Actor.stopped
    })

  private def waitingForKeyOwnershipProof(
      zoneValidatorShardRegion: typed.ActorRef[SerializableZoneValidatorMessage],
      remoteAddress: InetAddress,
      webSocketOut: ActorRef[proto.ws.protocol.ClientMessage],
      log: LoggingAdapter,
      mediator: ActorRef[Publish],
      pingGeneratorActor: typed.ActorRef[PingGeneratorActor.PingGeneratorMessage],
      keyOwnershipChallengeMessage: proto.ws.protocol.ClientMessage.KeyOwnershipChallenge)
    : Behavior[ClientConnectionMessage] =
    Actor.immutable[ClientConnectionMessage] { (context, message) =>
      message match {
        case actorSinkInit: InitActorSink =>
          log.warning(
            s"Stopping due to unexpected message; required CompleteKeyOwnershipProof but received $actorSinkInit")
          Actor.stopped

        case PublishClientStatusTick =>
          Actor.same

        case SendPingTick =>
          sendPingCommand(webSocketOut, pingGeneratorActor)
          Actor.same

        case ActorFlowServerMessage(webSocketIn, serverMessage) =>
          webSocketIn ! ActorSinkAck
          pingGeneratorActor ! PingGeneratorActor.FrameReceivedEvent
          serverMessage.message match {
            case other @ (proto.ws.protocol.ServerMessage.Message.Empty |
                _: proto.ws.protocol.ServerMessage.Message.Command |
                _: proto.ws.protocol.ServerMessage.Message.Response) =>
              log.warning(s"Stopping due to unexpected message; required CompleteKeyOwnershipProof but received $other")
              Actor.stopped

            case proto.ws.protocol.ServerMessage.Message.KeyOwnershipProof(keyOwnershipProofMessage) =>
              val publicKey = PublicKey(keyOwnershipProofMessage.publicKey.toByteArray)
              if (!Authentication.isValidKeyOwnershipProof(keyOwnershipChallengeMessage, keyOwnershipProofMessage)) {
                log.warning(
                  "Stopping due to invalid key ownership proof for public key with fingerprint " +
                    s"${publicKey.fingerprint}.")
                Actor.stopped
              } else {
                context.self ! PublishClientStatusTick
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
          Actor.stopped

        case zoneNotificationEnvelope: ZoneNotificationEnvelope =>
          log.warning(
            "Stopping due to unexpected message; required CompleteKeyOwnershipProof but received " +
              s"$zoneNotificationEnvelope")
          Actor.stopped
      }
    } onSignal {
      case (_, PostStop) =>
        log.info(s"Stopped for $remoteAddress")
        Actor.same
    }

  private def receiveActorSinkMessages(
      zoneValidatorShardRegion: typed.ActorRef[SerializableZoneValidatorMessage],
      remoteAddress: InetAddress,
      webSocketOut: ActorRef[proto.ws.protocol.ClientMessage],
      log: LoggingAdapter,
      mediator: ActorRef[Publish],
      pingGeneratorActor: typed.ActorRef[PingGeneratorActor.PingGeneratorMessage],
      publicKey: PublicKey,
      notificationSequenceNumbers: Map[typed.ActorRef[ZoneValidatorMessage], Long]): Behavior[ClientConnectionMessage] =
    Actor.immutable[ClientConnectionMessage] { (context, message) =>
      message match {
        case actorSinkInit: InitActorSink =>
          log.warning(s"Stopping due to unexpected message; received $actorSinkInit")
          Actor.stopped

        case PublishClientStatusTick =>
          mediator ! Publish(
            ClientMonitorActor.ClientStatusTopic,
            UpsertActiveClientSummary(context.self, ActiveClientSummary(publicKey))
          )
          Actor.same

        case SendPingTick =>
          sendPingCommand(webSocketOut, pingGeneratorActor)
          Actor.same

        case ActorFlowServerMessage(webSocketIn, serverMessage) =>
          webSocketIn ! ActorSinkAck
          pingGeneratorActor ! PingGeneratorActor.FrameReceivedEvent
          serverMessage.message match {
            case other @ (proto.ws.protocol.ServerMessage.Message.Empty |
                _: proto.ws.protocol.ServerMessage.Message.KeyOwnershipProof) =>
              log.warning(s"Stopping due to unexpected message; required Command or Response but received $other")
              Actor.stopped

            case proto.ws.protocol.ServerMessage.Message.Command(protoCommand) =>
              protoCommand.command match {
                case proto.ws.protocol.ServerMessage.Command.Command.Empty =>
                  Actor.same

                case proto.ws.protocol.ServerMessage.Command.Command.CreateZoneCommand(protoCreateZoneCommand) =>
                  val createZoneCommand =
                    ProtoBinding[CreateZoneCommand, proto.ws.protocol.ZoneCommand.CreateZoneCommand, Any]
                      .asScala(protoCreateZoneCommand)(())
                  zoneValidatorShardRegion ! ZoneCommandEnvelope(
                    context.self,
                    zoneId = ZoneId(UUID.randomUUID.toString),
                    remoteAddress,
                    publicKey,
                    protoCommand.correlationId,
                    createZoneCommand
                  )
                  Actor.same

                case proto.ws.protocol.ServerMessage.Command.Command.ZoneCommandEnvelope(
                    proto.ws.protocol.ServerMessage.Command.ZoneCommandEnvelope(_, None)
                    ) =>
                  Actor.same

                case proto.ws.protocol.ServerMessage.Command.Command.ZoneCommandEnvelope(
                    proto.ws.protocol.ServerMessage.Command.ZoneCommandEnvelope(zoneId, Some(protoZoneCommand))
                    ) =>
                  val zoneCommand =
                    ProtoBinding[ZoneCommand, proto.ws.protocol.ZoneCommand, Any].asScala(protoZoneCommand)(())
                  zoneCommand match {
                    case _: CreateZoneCommand =>
                      log.warning(s"Stopping due to receipt of illegally enveloped CreateZoneCommand")
                      Actor.stopped

                    case _ =>
                      zoneValidatorShardRegion ! ZoneCommandEnvelope(
                        context.self,
                        zoneId = ZoneId(zoneId),
                        remoteAddress,
                        publicKey,
                        protoCommand.correlationId,
                        zoneCommand
                      )
                      Actor.same
                  }
              }

            case proto.ws.protocol.ServerMessage.Message.Response(protoResponse) =>
              protoResponse.response match {
                case proto.ws.protocol.ServerMessage.Response.Response.Empty =>
                  log.warning("Stopping due to unexpected message; required PingResponse but received Empty")
                  Actor.stopped

                case proto.ws.protocol.ServerMessage.Response.Response.PingResponse(_) =>
                  Actor.same
              }
          }

        case ZoneResponseEnvelope(zoneValidator, correlationId, zoneResponse) =>
          sendClientMessage(
            webSocketOut,
            pingGeneratorActor,
            proto.ws.protocol.ClientMessage.Message.Response(proto.ws.protocol.ClientMessage.Response(
              correlationId,
              proto.ws.protocol.ClientMessage.Response.Response.ZoneResponse(
                ProtoBinding[ZoneResponse, proto.ws.protocol.ZoneResponse, Any].asProto(zoneResponse)(())
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
              Actor.same
          }

        case ZoneNotificationEnvelope(zoneValidator, zoneId, sequenceNumber, zoneNotification) =>
          notificationSequenceNumbers.get(zoneValidator) match {
            case None =>
              log.warning(
                "Stopping due to unexpected notification (JoinZoneCommand sent but no JoinZoneResponse received)")
              Actor.stopped

            case Some(expectedSequenceNumber) =>
              if (sequenceNumber != expectedSequenceNumber) {
                log.warning(s"Stopping due to unexpected notification ($sequenceNumber != $expectedSequenceNumber)")
                Actor.stopped
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
                              .asProto(zoneNotification)(()))
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
              }
          }
      }
    } onSignal {
      case (_, PostStop) =>
        log.info(s"Stopped for $remoteAddress")
        Actor.same

      case (_, Terminated(ref)) =>
        log.warning(s"Stopping due to termination of joined zone $ref")
        Actor.stopped
    }

  private def sendPingCommand(webSocketOut: ActorRef[proto.ws.protocol.ClientMessage],
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

  private def sendClientMessage(webSocketOut: ActorRef[proto.ws.protocol.ClientMessage],
                                pingGeneratorActor: typed.ActorRef[PingGeneratorActor.PingGeneratorMessage],
                                clientMessage: proto.ws.protocol.ClientMessage.Message): Unit = {
    webSocketOut ! proto.ws.protocol.ClientMessage(clientMessage)
    pingGeneratorActor ! PingGeneratorActor.FrameSentEvent
  }
}
