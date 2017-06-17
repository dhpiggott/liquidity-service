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
import com.dhpcs.liquidity.actor.protocol.{ZoneValidatorMessage, _}
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
              context.watch(context.actorOf(props(outActor).withDeploy(Deploy.local)))
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

  final case class WrappedCommand(jsonRpcRequestMessage: JsonRpcRequestMessage)
      extends NoSerializationVerificationNeeded

  sealed abstract class WrappedResponseOrNotification extends NoSerializationVerificationNeeded
  final case class WrappedResponse(jsonRpcResponseMessage: JsonRpcResponseMessage)
      extends WrappedResponseOrNotification
  final case class WrappedNotification(jsonRpcNotificationMessage: JsonRpcNotificationMessage)
      extends WrappedResponseOrNotification

  case object ActorSinkInit extends NoSerializationVerificationNeeded
  case object ActorSinkAck  extends NoSerializationVerificationNeeded

  private case object PublishStatus extends NoSerializationVerificationNeeded

  private object KeepAliveGeneratorActor {

    def props(keepAliveInterval: FiniteDuration): Props = Props(classOf[KeepAliveGeneratorActor], keepAliveInterval)

    case object FrameReceivedEvent extends NoSerializationVerificationNeeded
    case object FrameSentEvent     extends NoSerializationVerificationNeeded
    case object SendKeepAlive      extends NoSerializationVerificationNeeded

  }

  private class KeepAliveGeneratorActor(keepAliveInterval: FiniteDuration) extends Actor {

    import com.dhpcs.liquidity.server.actor.LegacyClientConnectionActor.KeepAliveGeneratorActor._

    context.setReceiveTimeout(keepAliveInterval)

    override def receive: Receive = {
      case ReceiveTimeout                      => context.parent ! SendKeepAlive
      case FrameReceivedEvent | FrameSentEvent => ()
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
    with AtLeastOnceDelivery {

  import com.dhpcs.liquidity.server.actor.LegacyClientConnectionActor.KeepAliveGeneratorActor._
  import context.dispatcher

  private[this] val mediator          = DistributedPubSub(context.system).mediator
  private[this] val publishStatusTick = context.system.scheduler.schedule(0.seconds, 30.seconds, self, PublishStatus)
  private[this] val keepAliveGeneratorActor =
    context.actorOf(KeepAliveGeneratorActor.props(keepAliveInterval).withDeploy(Deploy.local))

  private[this] var nextExpectedMessageSequenceNumbers = Map.empty[ActorRef, Long].withDefaultValue(1L)
  private[this] var commandSequenceNumbers             = Map.empty[ZoneId, Long].withDefaultValue(1L)
  private[this] var pendingDeliveries                  = Map.empty[ZoneId, Set[Long]].withDefaultValue(Set.empty)

  override def persistenceId: String = self.path.name

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
    publishStatus orElse sendKeepAlive orElse {
      case ActorSinkInit =>
        sender() ! ActorSinkAck
        sendNotification(SupportedVersionsNotification(CompatibleVersionNumbers))
        context.become(receiveActorSinkMessages)
    }

  private[this] def receiveActorSinkMessages: Receive =
    publishStatus orElse commandReceivedConfirmation orElse sendKeepAlive orElse {
      case WrappedCommand(jsonRpcRequestMessage) =>
        sender() ! ActorSinkAck
        keepAliveGeneratorActor ! FrameReceivedEvent
        Command.read(jsonRpcRequestMessage) match {
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
                  case CreateZoneCommand(equityOwnerPublicKey,
                                         equityOwnerName,
                                         equityOwnerMetadata,
                                         equityAccountName,
                                         equityAccountMetadata,
                                         name,
                                         metadata) =>
                    ZoneId.generate -> ZoneValidatorMessage.CreateZoneCommand(equityOwnerPublicKey,
                                                                              equityOwnerName,
                                                                              equityOwnerMetadata,
                                                                              equityAccountName,
                                                                              equityAccountMetadata,
                                                                              name,
                                                                              metadata)
                  case zoneCommand @ JoinZoneCommand(_) =>
                    zoneCommand.zoneId -> ZoneValidatorMessage.JoinZoneCommand
                  case zoneCommand @ QuitZoneCommand(_) =>
                    zoneCommand.zoneId -> ZoneValidatorMessage.QuitZoneCommand
                  case zoneCommand @ ChangeZoneNameCommand(_, name) =>
                    zoneCommand.zoneId -> ZoneValidatorMessage.ChangeZoneNameCommand(name)
                  case zoneCommand @ CreateMemberCommand(_, ownerPublicKey, name, metadata) =>
                    zoneCommand.zoneId -> ZoneValidatorMessage.CreateMemberCommand(ownerPublicKey, name, metadata)
                  case zoneCommand @ UpdateMemberCommand(_, member) =>
                    zoneCommand.zoneId -> ZoneValidatorMessage.UpdateMemberCommand(member)
                  case zoneCommand @ CreateAccountCommand(_, ownerMemberIds, name, metadata) =>
                    zoneCommand.zoneId -> ZoneValidatorMessage.CreateAccountCommand(ownerMemberIds, name, metadata)
                  case zoneCommand @ UpdateAccountCommand(_, account) =>
                    zoneCommand.zoneId -> ZoneValidatorMessage.UpdateAccountCommand(account)
                  case zoneCommand @ AddTransactionCommand(_, actingAs, from, to, value, description, metadata) =>
                    zoneCommand.zoneId -> ZoneValidatorMessage.AddTransactionCommand(actingAs,
                                                                                     from,
                                                                                     to,
                                                                                     value,
                                                                                     description,
                                                                                     metadata)
                }
                deliverZoneCommand(zoneId, zoneCommand, numericCorrelationId.value.toLongExact)
            }
        }
      case ZoneAlreadyExists(createZoneCommand, correlationId, sequenceNumber, deliveryId) =>
        exactlyOnce(sequenceNumber, deliveryId) {
          val zoneId         = ZoneId.generate
          val sequenceNumber = commandSequenceNumbers(zoneId)
          commandSequenceNumbers = commandSequenceNumbers + (zoneId -> (sequenceNumber + 1))
          deliver(zoneValidatorShardRegion.path) { deliveryId =>
            pendingDeliveries = pendingDeliveries + (zoneId -> (pendingDeliveries(zoneId) + deliveryId))
            EnvelopedZoneCommand(
              zoneId,
              createZoneCommand,
              publicKey,
              correlationId,
              sequenceNumber,
              deliveryId
            )
          }
        }
      case EnvelopedZoneResponse(zoneResponse, correlationId, sequenceNumber, deliveryId) =>
        exactlyOnce(sequenceNumber, deliveryId) {
          val response = zoneResponse match {
            case ZoneValidatorMessage.EmptyZoneResponse =>
              sys.error("Inconceivable")
            case ZoneValidatorMessage.ErrorResponse(error) =>
              ErrorResponse(error)
            case successResponse: ZoneValidatorMessage.SuccessResponse =>
              successResponse match {
                case ZoneValidatorMessage.CreateZoneResponse(zone) =>
                  CreateZoneResponse(zone)
                case ZoneValidatorMessage.JoinZoneResponse(zone, connectedClients) =>
                  JoinZoneResponse(zone, connectedClients)
                case ZoneValidatorMessage.QuitZoneResponse =>
                  QuitZoneResponse
                case ZoneValidatorMessage.ChangeZoneNameResponse =>
                  ChangeZoneNameResponse
                case ZoneValidatorMessage.CreateMemberResponse(member) =>
                  CreateMemberResponse(member)
                case ZoneValidatorMessage.UpdateMemberResponse =>
                  UpdateMemberResponse
                case ZoneValidatorMessage.CreateAccountResponse(account) =>
                  CreateAccountResponse(account)
                case ZoneValidatorMessage.UpdateAccountResponse =>
                  UpdateAccountResponse
                case ZoneValidatorMessage.AddTransactionResponse(transaction) =>
                  AddTransactionResponse(transaction)
              }
          }
          sendResponse(response, correlationId)
        }
      case EnvelopedZoneNotification(zoneId, zoneNotification, sequenceNumber, deliveryId) =>
        exactlyOnce(sequenceNumber, deliveryId) {
          val notification = zoneNotification match {
            case ZoneValidatorMessage.EmptyZoneNotification =>
              sys.error("Inconceivable")
            case ZoneValidatorMessage.ClientJoinedZoneNotification(_publicKey) =>
              ClientJoinedZoneNotification(zoneId, _publicKey)
            case ZoneValidatorMessage.ClientQuitZoneNotification(_publicKey) =>
              ClientQuitZoneNotification(zoneId, _publicKey)
            case ZoneValidatorMessage.ZoneTerminatedNotification =>
              ZoneTerminatedNotification(zoneId)
            case ZoneValidatorMessage.ZoneNameChangedNotification(name) =>
              ZoneNameChangedNotification(zoneId, name)
            case ZoneValidatorMessage.MemberCreatedNotification(member) =>
              MemberCreatedNotification(zoneId, member)
            case ZoneValidatorMessage.MemberUpdatedNotification(member) =>
              MemberUpdatedNotification(zoneId, member)
            case ZoneValidatorMessage.AccountCreatedNotification(account) =>
              AccountCreatedNotification(zoneId, account)
            case ZoneValidatorMessage.AccountUpdatedNotification(account) =>
              AccountUpdatedNotification(zoneId, account)
            case ZoneValidatorMessage.TransactionAddedNotification(transaction) =>
              TransactionAddedNotification(zoneId, transaction)
          }
          sendNotification(notification)
        }
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

  private[this] def sendKeepAlive: Receive = {
    case SendKeepAlive => sendNotification(KeepAliveNotification)
  }

  private[this] def deliverZoneCommand(zoneId: ZoneId,
                                       zoneCommand: ZoneValidatorMessage.ZoneCommand,
                                       correlationId: Long) = {
    val sequenceNumber = commandSequenceNumbers(zoneId)
    commandSequenceNumbers = commandSequenceNumbers + (zoneId -> (sequenceNumber + 1))
    deliver(zoneValidatorShardRegion.path) { deliveryId =>
      pendingDeliveries = pendingDeliveries + (zoneId -> (pendingDeliveries(zoneId) + deliveryId))
      EnvelopedZoneCommand(
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

  private[this] def sendNotification(notification: Notification): Unit =
    send(WrappedNotification(Notification.write(notification)))

  private[this] def sendResponse(response: Response, correlationId: Long): Unit =
    response match {
      case ErrorResponse(error) =>
        sendErrorResponse(error, NumericCorrelationId(correlationId))
      case successResponse: SuccessResponse =>
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

  private[this] def sendSuccessResponse(response: SuccessResponse, correlationId: Long): Unit =
    send(WrappedResponse(SuccessResponse.write(response, NumericCorrelationId(correlationId))))

  private[this] def send(wrappedResponseOrNotification: WrappedResponseOrNotification): Unit = {
    upstream ! wrappedResponseOrNotification
    keepAliveGeneratorActor ! FrameSentEvent
  }
}
