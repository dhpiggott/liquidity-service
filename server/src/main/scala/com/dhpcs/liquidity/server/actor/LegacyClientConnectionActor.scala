package com.dhpcs.liquidity.server.actor

import akka.actor._
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Publish
import akka.http.scaladsl.model.RemoteAddress
import akka.http.scaladsl.model.ws.{TextMessage, Message => WsMessage}
import akka.persistence.{AtLeastOnceDelivery, PersistentActor}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}
import akka.typed.Behavior
import akka.typed.scaladsl.adapter._
import akka.{NotUsed, typed}
import cats.data.Validated.{Invalid, Valid}
import cats.data.ValidatedNel
import com.dhpcs.jsonrpc.JsonRpcMessage.{CorrelationId, NoCorrelationId, NumericCorrelationId, StringCorrelationId}
import com.dhpcs.jsonrpc._
import com.dhpcs.liquidity.actor.protocol.clientconnection._
import com.dhpcs.liquidity.actor.protocol.clientmonitor._
import com.dhpcs.liquidity.actor.protocol.zonevalidator._
import com.dhpcs.liquidity.model.{PublicKey, ZoneId}
import com.dhpcs.liquidity.server.actor.LegacyClientConnectionActor._
import com.dhpcs.liquidity.ws.protocol.legacy._
import play.api.libs.json.{JsError, JsSuccess, Json}

import scala.concurrent.duration._

object LegacyClientConnectionActor {

  def props(ip: RemoteAddress,
            publicKey: PublicKey,
            zoneValidatorShardRegion: ActorRef,
            keepAliveInterval: FiniteDuration)(upstream: ActorRef): Props =
    Props(
      classOf[LegacyClientConnectionActor],
      ip,
      publicKey,
      zoneValidatorShardRegion,
      keepAliveInterval,
      upstream
    )

  def webSocketFlow(props: ActorRef => Props)(implicit factory: ActorRefFactory,
                                              mat: Materializer): Flow[WsMessage, WsMessage, NotUsed] =
    InFlow
      .via(actorFlow[WrappedCommand, WrappedResponseOrNotification](props))
      .via(OutFlow)

  private final val InFlow: Flow[WsMessage, WrappedCommand, NotUsed] =
    Flow[WsMessage].flatMapConcat(wsMessage =>
      for (jsonString <- wsMessage.asTextMessage match {
             case TextMessage.Streamed(textStream) => textStream.fold("")(_ + _)
             case TextMessage.Strict(text)         => Source.single(text)
           }) yield WrappedCommand(Json.parse(jsonString).as[JsonRpcRequestMessage]))

  private final val OutFlow: Flow[WrappedResponseOrNotification, WsMessage, NotUsed] =
    Flow[WrappedResponseOrNotification].map {
      case WrappedResponse(jsonRpcResponseMessage) =>
        TextMessage(Json.stringify(Json.toJson(jsonRpcResponseMessage)))
      case WrappedNotification(jsonRpcNotificationMessage) =>
        TextMessage(Json.stringify(Json.toJson(jsonRpcNotificationMessage)))
    }

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

  final case class WrappedCommand(jsonRpcRequestMessage: JsonRpcRequestMessage)

  sealed abstract class WrappedResponseOrNotification
  final case class WrappedResponse(jsonRpcResponseMessage: JsonRpcResponseMessage) extends WrappedResponseOrNotification
  final case class WrappedNotification(jsonRpcNotificationMessage: JsonRpcNotificationMessage)
      extends WrappedResponseOrNotification

  case object ActorSinkInit
  case object ActorSinkAck

  private case object PublishStatusTimerKey
  private case object PublishStatus

  private object KeepAliveGeneratorActor {

    sealed abstract class KeepAliveGeneratorMessage
    case object FrameReceivedEvent extends KeepAliveGeneratorMessage
    case object FrameSentEvent     extends KeepAliveGeneratorMessage
    case object SendKeepAlive      extends KeepAliveGeneratorMessage

    def behaviour(keepAliveInterval: FiniteDuration, parent: ActorRef): Behavior[KeepAliveGeneratorMessage] =
      typed.scaladsl.Actor.deferred { context =>
        context.setReceiveTimeout(keepAliveInterval, SendKeepAlive)
        typed.scaladsl.Actor.immutable[KeepAliveGeneratorMessage] { (_, message) =>
          message match {
            case FrameReceivedEvent | FrameSentEvent => ()
            case SendKeepAlive                       => parent ! SendKeepAlive
          }
          typed.scaladsl.Actor.same
        }
      }
  }
}

class LegacyClientConnectionActor(ip: RemoteAddress,
                                  publicKey: PublicKey,
                                  zoneValidatorShardRegion: ActorRef,
                                  keepAliveInterval: FiniteDuration,
                                  upstream: ActorRef)
    extends PersistentActor
    with ActorLogging
    with AtLeastOnceDelivery
    with Timers {

  import com.dhpcs.liquidity.server.actor.LegacyClientConnectionActor.KeepAliveGeneratorActor._

  private[this] val mediator = DistributedPubSub(context.system).mediator
  private[this] val keepAliveGeneratorActor = context.spawn(
    akka.typed.scaladsl.Actor
      .supervise(KeepAliveGeneratorActor.behaviour(keepAliveInterval, self))
      .onFailure[Exception](akka.typed.SupervisorStrategy.restart),
    "keep-alive-generator"
  )

  private[this] var nextExpectedMessageSequenceNumbers = Map.empty[ActorRef, Long].withDefaultValue(1L)
  private[this] var commandSequenceNumbers             = Map.empty[ZoneId, Long].withDefaultValue(1L)
  private[this] var pendingDeliveries                  = Map.empty[ZoneId, Set[Long]].withDefaultValue(Set.empty)

  timers.startPeriodicTimer(PublishStatusTimerKey, PublishStatus, 30.seconds)

  override def persistenceId: String = self.path.name

  override def preStart(): Unit = {
    super.preStart()
    log.info(s"Started for ${ip.toOption.getOrElse("unknown IP")}")
  }

  override def postStop(): Unit = {
    super.postStop()
    log.info(s"Stopped for ${ip.toOption.getOrElse("unknown IP")}")
  }

  override def receiveCommand: Receive = waitingForActorSinkInit

  private[this] def waitingForActorSinkInit: Receive =
    publishStatus orElse sendKeepAlive orElse {
      case ActorSinkInit =>
        sender() ! ActorSinkAck
        sendNotification(LegacyWsProtocol.SupportedVersionsNotification(LegacyWsProtocol.CompatibleVersionNumbers))
        context.become(receiveActorSinkMessages)
    }

  private[this] def receiveActorSinkMessages: Receive =
    publishStatus orElse commandReceivedConfirmation orElse sendKeepAlive orElse {
      case WrappedCommand(jsonRpcRequestMessage) =>
        sender() ! ActorSinkAck
        keepAliveGeneratorActor ! FrameReceivedEvent
        LegacyWsProtocol.Command.read(jsonRpcRequestMessage) match {
          case error: JsError =>
            log.warning(s"Receive error: $error")
            send(
              WrappedResponse(
                JsonRpcResponseErrorMessage.invalidRequest(error, jsonRpcRequestMessage.id)
              )
            )
          case JsSuccess(command, _) =>
            jsonRpcRequestMessage.id match {
              case NoCorrelationId =>
                sendErrorResponse(
                  error = "Correlation ID must be set",
                  correlationId = jsonRpcRequestMessage.id
                )
              case StringCorrelationId(_) =>
                sendErrorResponse(
                  error = "String correlation IDs are not supported",
                  correlationId = jsonRpcRequestMessage.id
                )
              case NumericCorrelationId(value) if !value.isValidLong =>
                sendErrorResponse(
                  error = "Floating point correlation IDs are not supported",
                  correlationId = jsonRpcRequestMessage.id
                )
              case numericCorrelationId: NumericCorrelationId =>
                val (zoneId, zoneCommand) = command match {
                  case LegacyWsProtocol.CreateZoneCommand(equityOwnerPublicKey,
                                                          equityOwnerName,
                                                          equityOwnerMetadata,
                                                          equityAccountName,
                                                          equityAccountMetadata,
                                                          name,
                                                          metadata) =>
                    ZoneId.generate -> CreateZoneCommand(equityOwnerPublicKey,
                                                         equityOwnerName,
                                                         equityOwnerMetadata,
                                                         equityAccountName,
                                                         equityAccountMetadata,
                                                         name,
                                                         metadata)
                  case zoneCommand @ LegacyWsProtocol.JoinZoneCommand(_) =>
                    zoneCommand.zoneId -> JoinZoneCommand
                  case zoneCommand @ LegacyWsProtocol.QuitZoneCommand(_) =>
                    zoneCommand.zoneId -> QuitZoneCommand
                  case zoneCommand @ LegacyWsProtocol.ChangeZoneNameCommand(_, name) =>
                    zoneCommand.zoneId -> ChangeZoneNameCommand(name)
                  case zoneCommand @ LegacyWsProtocol.CreateMemberCommand(_, ownerPublicKey, name, metadata) =>
                    zoneCommand.zoneId -> CreateMemberCommand(Set(ownerPublicKey), name, metadata)
                  case zoneCommand @ LegacyWsProtocol.UpdateMemberCommand(_, member) =>
                    zoneCommand.zoneId -> UpdateMemberCommand(member)
                  case zoneCommand @ LegacyWsProtocol.CreateAccountCommand(_, ownerMemberIds, name, metadata) =>
                    zoneCommand.zoneId -> CreateAccountCommand(ownerMemberIds, name, metadata)
                  case zoneCommand @ LegacyWsProtocol.UpdateAccountCommand(_, account) =>
                    zoneCommand.zoneId -> UpdateAccountCommand(actingAs = account.ownerMemberIds.head, account)
                  case zoneCommand @ LegacyWsProtocol.AddTransactionCommand(_,
                                                                            actingAs,
                                                                            from,
                                                                            to,
                                                                            value,
                                                                            description,
                                                                            metadata) =>
                    zoneCommand.zoneId -> AddTransactionCommand(actingAs, from, to, value, description, metadata)
                }
                deliverZoneCommand(zoneId, zoneCommand, numericCorrelationId.value.toLongExact)
            }
        }
      case ZoneResponseEnvelope(zoneResponse, correlationId, sequenceNumber, deliveryId) =>
        exactlyOnce(sequenceNumber, deliveryId) {
          def toLegacyResponse[A, B <: LegacyWsProtocol.SuccessResponse](
              validated: ValidatedNel[ZoneResponse.Error, A])(successResponse: A => B): LegacyWsProtocol.ZoneResponse =
            validated match {
              case Invalid(e) => LegacyWsProtocol.ErrorResponse(e.map(_.description).toList.mkString(","))
              case Valid(a)   => successResponse(a)
            }
          val response = zoneResponse match {
            case EmptyZoneResponse =>
              throw new IllegalArgumentException("Inconceivable")
            case CreateZoneResponse(result) =>
              toLegacyResponse(result)(LegacyWsProtocol.CreateZoneResponse)
            case JoinZoneResponse(result) =>
              context.watch(sender())
              toLegacyResponse(result)(LegacyWsProtocol.JoinZoneResponse.tupled)
            case QuitZoneResponse(result) =>
              context.unwatch(sender())
              toLegacyResponse(result)(_ => LegacyWsProtocol.QuitZoneResponse)
            case ChangeZoneNameResponse(result) =>
              toLegacyResponse(result)(_ => LegacyWsProtocol.ChangeZoneNameResponse)
            case CreateMemberResponse(result) =>
              toLegacyResponse(result)(LegacyWsProtocol.CreateMemberResponse)
            case UpdateMemberResponse(result) =>
              toLegacyResponse(result)(_ => LegacyWsProtocol.UpdateMemberResponse)
            case CreateAccountResponse(result) =>
              toLegacyResponse(result)(LegacyWsProtocol.CreateAccountResponse)
            case UpdateAccountResponse(result) =>
              toLegacyResponse(result)(_ => LegacyWsProtocol.UpdateAccountResponse)
            case AddTransactionResponse(result) =>
              toLegacyResponse(result)(LegacyWsProtocol.AddTransactionResponse)
          }
          sendResponse(response, correlationId)
        }
      case ZoneNotificationEnvelope(zoneId, zoneNotification, sequenceNumber, deliveryId) =>
        exactlyOnce(sequenceNumber, deliveryId) {
          val notification = zoneNotification match {
            case EmptyZoneNotification =>
              throw new IllegalArgumentException("Inconceivable")
            case ClientJoinedZoneNotification(_publicKey) =>
              LegacyWsProtocol.ClientJoinedZoneNotification(zoneId, _publicKey)
            case ClientQuitZoneNotification(_publicKey) =>
              LegacyWsProtocol.ClientQuitZoneNotification(zoneId, _publicKey)
            case ZoneNameChangedNotification(name) =>
              LegacyWsProtocol.ZoneNameChangedNotification(zoneId, name)
            case MemberCreatedNotification(member) =>
              LegacyWsProtocol.MemberCreatedNotification(zoneId, member)
            case MemberUpdatedNotification(member) =>
              LegacyWsProtocol.MemberUpdatedNotification(zoneId, member)
            case AccountCreatedNotification(account) =>
              LegacyWsProtocol.AccountCreatedNotification(zoneId, account)
            case AccountUpdatedNotification(account) =>
              LegacyWsProtocol.AccountUpdatedNotification(zoneId, account)
            case TransactionAddedNotification(transaction) =>
              LegacyWsProtocol.TransactionAddedNotification(zoneId, transaction)
          }
          sendNotification(notification)
        }
      case Terminated(_) =>
        context.stop(self)
    }

  override def receiveRecover: Receive = Actor.emptyBehavior

  private[this] def publishStatus: Receive = {
    case PublishStatus =>
      mediator ! Publish(
        ClientMonitorActor.ClientStatusTopic,
        UpsertActiveClientSummary(self, ActiveClientSummary(publicKey))
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

  private[this] def sendKeepAlive: Receive = {
    case SendKeepAlive => sendNotification(LegacyWsProtocol.KeepAliveNotification)
  }

  private[this] def deliverZoneCommand(zoneId: ZoneId, zoneCommand: ZoneCommand, correlationId: Long): Unit = {
    val sequenceNumber = commandSequenceNumbers(zoneId)
    commandSequenceNumbers = commandSequenceNumbers + (zoneId -> (sequenceNumber + 1))
    deliver(zoneValidatorShardRegion.path) { deliveryId =>
      pendingDeliveries = pendingDeliveries + (zoneId -> (pendingDeliveries(zoneId) + deliveryId))
      ZoneCommandEnvelope(
        zoneId,
        zoneCommand,
        publicKey,
        correlationId,
        sequenceNumber,
        deliveryId
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

  private[this] def sendNotification(notification: LegacyWsProtocol.Notification): Unit =
    send(WrappedNotification(LegacyWsProtocol.Notification.write(notification)))

  private[this] def sendResponse(response: LegacyWsProtocol.Response, correlationId: Long): Unit =
    response match {
      case LegacyWsProtocol.ErrorResponse(error) =>
        sendErrorResponse(error, NumericCorrelationId(correlationId))
      case successResponse: LegacyWsProtocol.SuccessResponse =>
        sendSuccessResponse(successResponse, correlationId)
    }

  private[this] def sendErrorResponse(error: String, correlationId: CorrelationId): Unit =
    send(
      WrappedResponse(
        JsonRpcResponseErrorMessage.applicationError(
          code = JsonRpcResponseErrorMessage.ReservedErrorCodeFloor - 1,
          message = error,
          data = None,
          correlationId
        )
      ))

  private[this] def sendSuccessResponse(response: LegacyWsProtocol.SuccessResponse, correlationId: Long): Unit =
    send(WrappedResponse(LegacyWsProtocol.SuccessResponse.write(response, NumericCorrelationId(correlationId))))

  private[this] def send(wrappedResponseOrNotification: WrappedResponseOrNotification): Unit = {
    upstream ! wrappedResponseOrNotification
    keepAliveGeneratorActor ! FrameSentEvent
  }
}
