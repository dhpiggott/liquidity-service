package com.dhpcs.liquidity.server.actor

import java.net.InetAddress
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.security.{KeyFactory, Signature}
import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, Behavior, PostStop, Terminated}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Publish
import akka.http.scaladsl.model.ws.{BinaryMessage, Message => WsMessage}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.typed.scaladsl.{ActorSink, ActorSource}
import akka.stream.{Materializer, OverflowStrategy}
import akka.util.ByteString
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
import scala.util.Random

object ClientConnectionActor {

  def webSocketFlow(
      pingInterval: FiniteDuration,
      zoneValidatorShardRegion: ActorRef[SerializableZoneValidatorMessage],
      remoteAddress: InetAddress)(
      implicit system: ActorSystem,
      mat: Materializer): Flow[WsMessage, WsMessage, NotUsed] =
    InFlow
      .via(
        ActorFlow.actorFlow(
          behavior(pingInterval, zoneValidatorShardRegion, remoteAddress)
        )
      )
      .via(OutFlow)

  private[this] final val InFlow
    : Flow[WsMessage, proto.ws.protocol.ServerMessage, NotUsed] =
    Flow[WsMessage].flatMapConcat(
      wsMessage =>
        for (byteString <- wsMessage.asBinaryMessage match {
               case BinaryMessage.Streamed(dataStream) =>
                 dataStream.fold(ByteString.empty)((acc, data) => acc ++ data)
               case BinaryMessage.Strict(data) => Source.single(data)
             })
          yield proto.ws.protocol.ServerMessage.parseFrom(byteString.toArray))

  private[this] object ActorFlow {

    private[this] sealed abstract class ActorFlowMessage
    private[this] final case class ForwardInit(
        webSocketIn: ActorRef[ActorSinkAck.type])
        extends ActorFlowMessage
    private[this] final case class ForwardMessage(
        webSocketIn: ActorRef[ActorSinkAck.type],
        serverMessage: proto.ws.protocol.ServerMessage)
        extends ActorFlowMessage
    private[this] case object Stop extends ActorFlowMessage

    def actorFlow(
        behavior: ActorRef[proto.ws.protocol.ClientMessage] => Behavior[
          ClientConnectionMessage])(
        implicit system: ActorSystem,
        mat: Materializer): Flow[proto.ws.protocol.ServerMessage,
                                 proto.ws.protocol.ClientMessage,
                                 NotUsed] = {
      val (outActor, publisher) = ActorSource
        .actorRef[proto.ws.protocol.ClientMessage](
          completionMatcher = PartialFunction.empty,
          failureMatcher = PartialFunction.empty,
          bufferSize = 16,
          overflowStrategy = OverflowStrategy.fail
        )
        .toMat(Sink.asPublisher(false))(Keep.both)
        .run()
      Flow.fromSinkAndSourceCoupled(
        ActorSink.actorRefWithAck[proto.ws.protocol.ServerMessage,
                                  ActorFlowMessage,
                                  ActorSinkAck.type](
          system.spawnAnonymous(
            Behaviors.setup[ActorFlowMessage] { context =>
              val flow = context.spawnAnonymous(behavior(outActor))
              context.watch(flow)
              Behaviors.immutable[ActorFlowMessage]((_, message) =>
                message match {
                  case ForwardInit(webSocketIn) =>
                    flow ! InitActorSink(webSocketIn)
                    Behaviors.same

                  case ForwardMessage(webSocketIn, serverMessage) =>
                    flow ! ActorFlowServerMessage(webSocketIn, serverMessage)
                    Behaviors.same

                  case Stop =>
                    Behaviors.stopped
              }) onSignal {
                case (_, Terminated(_)) =>
                  Behaviors.stopped
              }
            }
          ),
          messageAdapter = (webSocketIn, serverMessage) =>
            ForwardMessage(webSocketIn, serverMessage),
          onInitMessage = ForwardInit,
          ackMessage = ActorSinkAck,
          onCompleteMessage = Stop,
          onFailureMessage = _ => Stop
        ),
        Source.fromPublisher(publisher)
      )
    }
  }

  def behavior(
      pingInterval: FiniteDuration,
      zoneValidatorShardRegion: ActorRef[SerializableZoneValidatorMessage],
      remoteAddress: InetAddress)(
      webSocketOut: ActorRef[proto.ws.protocol.ClientMessage])
    : Behavior[ClientConnectionMessage] =
    Behaviors.setup(context =>
      Behaviors.withTimers { timers =>
        context.log.info(s"Starting for $remoteAddress")
        val mediator = DistributedPubSub(context.system.toUntyped).mediator
        timers.startPeriodicTimer(PublishStatusTimerKey,
                                  PublishClientStatusTick,
                                  30.seconds)
        val pingGenerator =
          context.spawn(PingGeneratorActor.behavior(pingInterval, context.self),
                        "pingGenerator")
        waitingForActorSinkInit(zoneValidatorShardRegion,
                                remoteAddress,
                                webSocketOut,
                                mediator,
                                pingGenerator)
    })

  private[this] case object PublishStatusTimerKey

  private[this] object PingGeneratorActor {

    sealed abstract class PingGeneratorMessage
    case object FrameReceivedEvent extends PingGeneratorMessage
    case object FrameSentEvent extends PingGeneratorMessage
    case object ReceiveTimeout extends PingGeneratorMessage

    def behavior(pingInterval: FiniteDuration,
                 clientConnection: ActorRef[SendPingTick.type])
      : Behavior[PingGeneratorMessage] =
      Behaviors.setup { context =>
        context.setReceiveTimeout(pingInterval, ReceiveTimeout)
        Behaviors.immutable[PingGeneratorMessage]((_, message) =>
          message match {
            case FrameReceivedEvent | FrameSentEvent =>
              Behaviors.same

            case ReceiveTimeout =>
              clientConnection ! SendPingTick
              Behaviors.same
        })
      }

  }

  private[this] def waitingForActorSinkInit(
      zoneValidatorShardRegion: ActorRef[SerializableZoneValidatorMessage],
      remoteAddress: InetAddress,
      webSocketOut: ActorRef[proto.ws.protocol.ClientMessage],
      mediator: ActorRef[Publish],
      pingGeneratorActor: ActorRef[PingGeneratorActor.PingGeneratorMessage])
    : Behavior[ClientConnectionMessage] =
    Behaviors.immutable[ClientConnectionMessage]((context, message) =>
      message match {
        case InitActorSink(webSocketIn) =>
          webSocketIn ! ActorSinkAck
          val keyOwnershipChallenge = createKeyOwnershipChallenge()
          sendClientMessage(
            webSocketOut,
            pingGeneratorActor,
            proto.ws.protocol.ClientMessage.Message
              .KeyOwnershipChallenge(keyOwnershipChallenge)
          )
          waitingForKeyOwnershipProof(zoneValidatorShardRegion,
                                      remoteAddress,
                                      webSocketOut,
                                      mediator,
                                      pingGeneratorActor,
                                      keyOwnershipChallenge)

        case PublishClientStatusTick | SendPingTick =>
          Behaviors.same

        case wrappedServerMessage: ActorFlowServerMessage =>
          context.log.warning(
            s"Stopping due to unexpected message; required ActorSinkInit " +
              s"but received $wrappedServerMessage")
          Behaviors.stopped

        case zoneResponseEnvelope: ZoneResponseEnvelope =>
          context.log.warning(
            s"Stopping due to unexpected message; required ActorSinkInit " +
              s"but received $zoneResponseEnvelope")
          Behaviors.stopped

        case zoneNotificationEnvelope: ZoneNotificationEnvelope =>
          context.log.warning(
            s"Stopping due to unexpected message; required ActorSinkInit " +
              s"but received $zoneNotificationEnvelope")
          Behaviors.stopped
    })

  private[this] def waitingForKeyOwnershipProof(
      zoneValidatorShardRegion: ActorRef[SerializableZoneValidatorMessage],
      remoteAddress: InetAddress,
      webSocketOut: ActorRef[proto.ws.protocol.ClientMessage],
      mediator: ActorRef[Publish],
      pingGeneratorActor: ActorRef[PingGeneratorActor.PingGeneratorMessage],
      keyOwnershipChallenge: proto.ws.protocol.ClientMessage.KeyOwnershipChallenge)
    : Behavior[ClientConnectionMessage] =
    Behaviors.immutable[ClientConnectionMessage] { (context, message) =>
      message match {
        case actorSinkInit: InitActorSink =>
          context.log.warning(
            s"Stopping due to unexpected message; required " +
              s"KeyOwnershipProof but received $actorSinkInit")
          Behaviors.stopped

        case PublishClientStatusTick =>
          Behaviors.same

        case SendPingTick =>
          sendPingCommand(webSocketOut, pingGeneratorActor)
          Behaviors.same

        case ActorFlowServerMessage(webSocketIn, serverMessage) =>
          webSocketIn ! ActorSinkAck
          pingGeneratorActor ! PingGeneratorActor.FrameReceivedEvent
          serverMessage.message match {
            case other @ (proto.ws.protocol.ServerMessage.Message.Empty |
                _: proto.ws.protocol.ServerMessage.Message.Command |
                _: proto.ws.protocol.ServerMessage.Message.Response) =>
              context.log.warning(
                s"Stopping due to unexpected message; required " +
                  s"KeyOwnershipProof but received $other")
              Behaviors.stopped

            case proto.ws.protocol.ServerMessage.Message
                  .KeyOwnershipProof(keyOwnershipProof) =>
              val publicKey = PublicKey(keyOwnershipProof.publicKey.toByteArray)
              if (!isValidKeyOwnershipProof(keyOwnershipChallenge,
                                            keyOwnershipProof)) {
                context.log.warning(
                  "Stopping due to invalid key ownership proof for public " +
                    "key with fingerprint " +
                    s"${publicKey.fingerprint}.")
                Behaviors.stopped
              } else {
                context.self ! PublishClientStatusTick
                receiveActorSinkMessages(
                  zoneValidatorShardRegion,
                  remoteAddress,
                  webSocketOut,
                  mediator,
                  pingGeneratorActor,
                  publicKey,
                  notificationSequenceNumbers = Map.empty)
              }
          }

        case zoneResponseEnvelope: ZoneResponseEnvelope =>
          context.log.warning(
            s"Stopping due to unexpected message; required " +
              s"KeyOwnershipProof but received $zoneResponseEnvelope")
          Behaviors.stopped

        case zoneNotificationEnvelope: ZoneNotificationEnvelope =>
          context.log.warning(
            s"Stopping due to unexpected message; required " +
              s"KeyOwnershipProof but received $zoneNotificationEnvelope")
          Behaviors.stopped
      }
    } onSignal {
      case (context, PostStop) =>
        context.log.info(s"Stopped for $remoteAddress")
        Behaviors.same
    }

  private[this] def receiveActorSinkMessages(
      zoneValidatorShardRegion: ActorRef[SerializableZoneValidatorMessage],
      remoteAddress: InetAddress,
      webSocketOut: ActorRef[proto.ws.protocol.ClientMessage],
      mediator: ActorRef[Publish],
      pingGeneratorActor: ActorRef[PingGeneratorActor.PingGeneratorMessage],
      publicKey: PublicKey,
      notificationSequenceNumbers: Map[ActorRef[ZoneValidatorMessage], Long])
    : Behavior[ClientConnectionMessage] =
    Behaviors.immutable[ClientConnectionMessage] { (context, message) =>
      message match {
        case actorSinkInit: InitActorSink =>
          context.log.warning(
            s"Stopping due to unexpected message; received $actorSinkInit")
          Behaviors.stopped

        case PublishClientStatusTick =>
          mediator ! Publish(
            ClientMonitorActor.ClientStatusTopic,
            UpsertActiveClientSummary(
              context.self,
              ActiveClientSummary(remoteAddress, publicKey)
            )
          )
          Behaviors.same

        case SendPingTick =>
          sendPingCommand(webSocketOut, pingGeneratorActor)
          Behaviors.same

        case ActorFlowServerMessage(webSocketIn, serverMessage) =>
          webSocketIn ! ActorSinkAck
          pingGeneratorActor ! PingGeneratorActor.FrameReceivedEvent
          serverMessage.message match {
            case other @ (proto.ws.protocol.ServerMessage.Message.Empty |
                _: proto.ws.protocol.ServerMessage.Message.KeyOwnershipProof) =>
              context.log.warning(
                s"Stopping due to unexpected message; required Command or " +
                  s"Response but received $other")
              Behaviors.stopped

            case proto.ws.protocol.ServerMessage.Message
                  .Command(protoCommand) =>
              protoCommand.command match {
                case proto.ws.protocol.ServerMessage.Command.Command.Empty =>
                  Behaviors.same

                case proto.ws.protocol.ServerMessage.Command.Command
                      .CreateZoneCommand(protoCreateZoneCommand) =>
                  val createZoneCommand =
                    ProtoBinding[
                      CreateZoneCommand,
                      proto.ws.protocol.ZoneCommand.CreateZoneCommand,
                      Any]
                      .asScala(protoCreateZoneCommand)(())
                  zoneValidatorShardRegion ! ZoneCommandEnvelope(
                    context.self,
                    zoneId = ZoneId(UUID.randomUUID().toString),
                    remoteAddress,
                    publicKey,
                    protoCommand.correlationId,
                    createZoneCommand
                  )
                  Behaviors.same

                case proto.ws.protocol.ServerMessage.Command.Command
                      .ZoneCommandEnvelope(
                      proto.ws.protocol.ServerMessage.Command
                        .ZoneCommandEnvelope(_, None)
                      ) =>
                  Behaviors.same

                case proto.ws.protocol.ServerMessage.Command.Command
                      .ZoneCommandEnvelope(
                      proto.ws.protocol.ServerMessage.Command
                        .ZoneCommandEnvelope(zoneId, Some(protoZoneCommand))
                      ) =>
                  val zoneCommand =
                    ProtoBinding[ZoneCommand,
                                 proto.ws.protocol.ZoneCommand,
                                 Any].asScala(protoZoneCommand)(())
                  zoneCommand match {
                    case _: CreateZoneCommand =>
                      context.log.warning(
                        s"Stopping due to receipt of illegally enveloped " +
                          s"CreateZoneCommand")
                      Behaviors.stopped

                    case _ =>
                      zoneValidatorShardRegion ! ZoneCommandEnvelope(
                        context.self,
                        zoneId = ZoneId(zoneId),
                        remoteAddress,
                        publicKey,
                        protoCommand.correlationId,
                        zoneCommand
                      )
                      Behaviors.same
                  }
              }

            case proto.ws.protocol.ServerMessage.Message
                  .Response(protoResponse) =>
              protoResponse.response match {
                case proto.ws.protocol.ServerMessage.Response.Response.Empty =>
                  context.log.warning(
                    "Stopping due to unexpected message; required " +
                      "PingResponse but received Empty")
                  Behaviors.stopped

                case proto.ws.protocol.ServerMessage.Response.Response
                      .PingResponse(_) =>
                  Behaviors.same
              }
          }

        case ZoneResponseEnvelope(zoneValidator, correlationId, zoneResponse) =>
          sendClientMessage(
            webSocketOut,
            pingGeneratorActor,
            proto.ws.protocol.ClientMessage.Message.Response(
              proto.ws.protocol.ClientMessage.Response(
                correlationId,
                proto.ws.protocol.ClientMessage.Response.Response.ZoneResponse(
                  ProtoBinding[ZoneResponse,
                               proto.ws.protocol.ZoneResponse,
                               Any].asProto(zoneResponse)(())
                )
              ))
          )
          zoneResponse match {
            case JoinZoneResponse(Valid(_)) =>
              context.watch(zoneValidator)
              receiveActorSinkMessages(
                zoneValidatorShardRegion,
                remoteAddress,
                webSocketOut,
                mediator,
                pingGeneratorActor,
                publicKey,
                notificationSequenceNumbers + (zoneValidator -> 0))

            case QuitZoneResponse(Valid(_)) =>
              context.unwatch(zoneValidator)
              receiveActorSinkMessages(
                zoneValidatorShardRegion,
                remoteAddress,
                webSocketOut,
                mediator,
                pingGeneratorActor,
                publicKey,
                notificationSequenceNumbers - zoneValidator)

            case _ =>
              Behaviors.same
          }

        case ZoneNotificationEnvelope(zoneValidator,
                                      zoneId,
                                      sequenceNumber,
                                      zoneNotification) =>
          notificationSequenceNumbers.get(zoneValidator) match {
            case None =>
              context.log.warning(
                "Stopping due to unexpected notification (JoinZoneCommand " +
                  "sent but no JoinZoneResponse received)")
              Behaviors.stopped

            case Some(expectedSequenceNumber) =>
              if (sequenceNumber != expectedSequenceNumber) {
                context.log.warning(
                  s"Stopping due to unexpected notification ($sequenceNumber != $expectedSequenceNumber)")
                Behaviors.stopped
              } else {
                sendClientMessage(
                  webSocketOut,
                  pingGeneratorActor,
                  proto.ws.protocol.ClientMessage.Message.Notification(
                    proto.ws.protocol.ClientMessage.Notification(
                      proto.ws.protocol.ClientMessage.Notification.Notification
                        .ZoneNotificationEnvelope(
                          proto.ws.protocol.ClientMessage.Notification
                            .ZoneNotificationEnvelope(
                              zoneId.value.toString,
                              Some(
                                ProtoBinding[ZoneNotification,
                                             proto.ws.protocol.ZoneNotification,
                                             Any]
                                  .asProto(zoneNotification)(()))
                            ))
                    ))
                )
                val nextExpectedSequenceNumber = expectedSequenceNumber + 1
                receiveActorSinkMessages(
                  zoneValidatorShardRegion,
                  remoteAddress,
                  webSocketOut,
                  mediator,
                  pingGeneratorActor,
                  publicKey,
                  notificationSequenceNumbers + (zoneValidator -> nextExpectedSequenceNumber)
                )
              }
          }
      }
    } onSignal {
      case (context, PostStop) =>
        context.log.info(s"Stopped for $remoteAddress")
        Behaviors.same

      case (context, Terminated(ref)) =>
        context.log.warning(s"Stopping due to termination of joined zone $ref")
        Behaviors.stopped
    }

  private[this] def sendPingCommand(
      webSocketOut: ActorRef[proto.ws.protocol.ClientMessage],
      pingGeneratorActor: ActorRef[PingGeneratorActor.PingGeneratorMessage])
    : Unit =
    sendClientMessage(
      webSocketOut,
      pingGeneratorActor,
      proto.ws.protocol.ClientMessage.Message.Command(
        proto.ws.protocol.ClientMessage.Command(
          correlationId = -1,
          command = proto.ws.protocol.ClientMessage.Command.Command
            .PingCommand(com.google.protobuf.ByteString.EMPTY)
        ))
    )

  private[this] def sendClientMessage(
      webSocketOut: ActorRef[proto.ws.protocol.ClientMessage],
      pingGeneratorActor: ActorRef[PingGeneratorActor.PingGeneratorMessage],
      clientMessage: proto.ws.protocol.ClientMessage.Message): Unit = {
    webSocketOut ! proto.ws.protocol.ClientMessage(clientMessage)
    pingGeneratorActor ! PingGeneratorActor.FrameSentEvent
  }

  private[this] final val KeySize = 2048

  private[this] def createKeyOwnershipChallenge()
    : proto.ws.protocol.ClientMessage.KeyOwnershipChallenge = {
    val nonce = new Array[Byte](KeySize / 8)
    Random.nextBytes(nonce)
    proto.ws.protocol.ClientMessage.KeyOwnershipChallenge(
      com.google.protobuf.ByteString.copyFrom(nonce)
    )
  }

  private[this] def isValidKeyOwnershipProof(
      keyOwnershipChallenge: proto.ws.protocol.ClientMessage.KeyOwnershipChallenge,
      keyOwnershipProof: proto.ws.protocol.ServerMessage.KeyOwnershipProof)
    : Boolean = {
    def isValidMessageSignature(publicKey: RSAPublicKey)(
        message: Array[Byte],
        signature: Array[Byte]): Boolean = {
      val s = Signature.getInstance("SHA256withRSA")
      s.initVerify(publicKey)
      s.update(message)
      s.verify(signature)
    }
    val publicKey = KeyFactory
      .getInstance("RSA")
      .generatePublic(
        new X509EncodedKeySpec(keyOwnershipProof.publicKey.toByteArray))
      .asInstanceOf[RSAPublicKey]
    val nonce = keyOwnershipChallenge.nonce.toByteArray
    val signature = keyOwnershipProof.signature.toByteArray
    isValidMessageSignature(publicKey)(nonce, signature)
  }

  private[this] final val OutFlow
    : Flow[proto.ws.protocol.ClientMessage, WsMessage, NotUsed] =
    Flow[proto.ws.protocol.ClientMessage].map(
      serverMessage => BinaryMessage(ByteString(serverMessage.toByteArray))
    )

}
