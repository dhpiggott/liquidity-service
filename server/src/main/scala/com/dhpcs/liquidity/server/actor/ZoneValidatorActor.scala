package com.dhpcs.liquidity.server.actor

import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.{InvalidKeySpecException, X509EncodedKeySpec}
import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorPath, ActorRef, Deploy, PoisonPill, Props, ReceiveTimeout, Terminated}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Publish
import akka.cluster.sharding.ShardRegion
import akka.persistence.{AtLeastOnceDelivery, PersistentActor, RecoveryCompleted}
import com.dhpcs.jsonrpc.JsonRpcMessage.CorrelationId
import com.dhpcs.jsonrpc.JsonRpcResponseErrorMessage
import com.dhpcs.liquidity.actor.protocol._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.persistence._
import com.dhpcs.liquidity.server.actor.ZoneValidatorActor._
import com.dhpcs.liquidity.ws.protocol._
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.duration._

object ZoneValidatorActor {

  def props: Props = Props(new ZoneValidatorActor)

  final val ShardTypeName = "zone-validator"

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case EnvelopedAuthenticatedCommandWithIds(zoneId, message) =>
      (zoneId.id.toString, message)
    case authenticatedCommandWithIds @ AuthenticatedCommandWithIds(_, zoneCommand: ZoneCommand, _, _, _) =>
      (zoneCommand.zoneId.id.toString, authenticatedCommandWithIds)
  }

  private val NumberOfShards = 10

  val extractShardId: ShardRegion.ExtractShardId = {
    case EnvelopedAuthenticatedCommandWithIds(zoneId, _) =>
      (math.abs(zoneId.id.hashCode) % NumberOfShards).toString
    case AuthenticatedCommandWithIds(_, zoneCommand: ZoneCommand, _, _, _) =>
      (math.abs(zoneCommand.zoneId.id.hashCode) % NumberOfShards).toString
  }

  final val Topic = "Zone"

  private final val RequiredOwnerKeyLength = 2048

  private val ZoneLifetime = 2.days

  private final case class State(zone: Zone = null,
                                 balances: Map[AccountId, BigDecimal] = Map.empty.withDefaultValue(BigDecimal(0)),
                                 clientConnections: Map[ActorPath, PublicKey] = Map.empty[ActorPath, PublicKey]) {

    def updated(event: Event): State = event match {
      case zoneCreatedEvent: ZoneCreatedEvent =>
        copy(
          zone = zoneCreatedEvent.zone
        )
      case ZoneJoinedEvent(_, clientConnectionActorPath, publicKey) =>
        copy(
          clientConnections = clientConnections + (clientConnectionActorPath -> publicKey)
        )
      case ZoneQuitEvent(_, clientConnectionActorPath) =>
        copy(
          clientConnections = clientConnections - clientConnectionActorPath
        )
      case ZoneNameChangedEvent(_, name) =>
        copy(
          zone = zone.copy(
            name = name
          )
        )
      case MemberCreatedEvent(_, member) =>
        copy(
          zone = zone.copy(
            members = zone.members + (member.id -> member)
          )
        )
      case MemberUpdatedEvent(_, member) =>
        copy(
          zone = zone.copy(
            members = zone.members + (member.id -> member)
          )
        )
      case AccountCreatedEvent(_, account) =>
        copy(
          zone = zone.copy(
            accounts = zone.accounts + (account.id -> account)
          )
        )
      case AccountUpdatedEvent(_, account) =>
        copy(
          zone = zone.copy(
            accounts = zone.accounts + (account.id -> account)
          )
        )
      case TransactionAddedEvent(_, transaction) =>
        val updatedSourceBalance      = balances(transaction.from) - transaction.value
        val updatedDestinationBalance = balances(transaction.to) + transaction.value
        copy(
          balances = balances +
            (transaction.from -> updatedSourceBalance) +
            (transaction.to   -> updatedDestinationBalance),
          zone = zone.copy(
            transactions = zone.transactions + (transaction.id -> transaction)
          )
        )
    }
  }

  private case object PublishStatus

  private object PassivationCountdownActor {

    def props: Props = Props(new PassivationCountdownActor)

    case object CommandReceivedEvent
    case object RequestPassivate
    case object Start
    case object Stop

    private final val PassivationTimeout = 2.minutes

  }

  private class PassivationCountdownActor extends Actor {

    import ZoneValidatorActor.PassivationCountdownActor._

    context.setReceiveTimeout(PassivationTimeout)

    override def receive: Receive = {
      case ReceiveTimeout       => context.parent ! RequestPassivate
      case CommandReceivedEvent =>
      case Start                => context.setReceiveTimeout(PassivationTimeout)
      case Stop                 => context.setReceiveTimeout(Duration.Undefined)
    }
  }

  private def checkAccountOwners(zone: Zone, owners: Set[MemberId]): Option[String] = {
    val invalidAccountOwners = owners -- zone.members.keys
    if (invalidAccountOwners.nonEmpty)
      Some(s"Invalid account owners: $invalidAccountOwners")
    else
      None
  }

  private def checkCanModify(zone: Zone, memberId: MemberId, publicKey: PublicKey): Option[String] =
    zone.members.get(memberId) match {
      case None => Some("Member does not exist")
      case Some(member) =>
        if (publicKey != member.ownerPublicKey)
          Some("Client's public key does not match Member's public key")
        else
          None
    }

  private def checkCanModify(zone: Zone, accountId: AccountId, publicKey: PublicKey): Option[String] =
    zone.accounts.get(accountId) match {
      case None => Some("Account does not exist")
      case Some(account) =>
        if (!account.ownerMemberIds.exists(
              memberId => zone.members.get(memberId).exists(publicKey == _.ownerPublicKey)))
          Some("Client's public key does not match that of any account owner member")
        else
          None
    }

  private def checkCanModify(zone: Zone,
                             accountId: AccountId,
                             actingAs: MemberId,
                             publicKey: PublicKey): Option[String] =
    zone.accounts.get(accountId) match {
      case None => Some("Account does not exist")
      case Some(account) =>
        if (!account.ownerMemberIds.contains(actingAs))
          Some("Member is not an account owner")
        else
          zone.members.get(actingAs) match {
            case None => Some("Member does not exist")
            case Some(member) =>
              if (publicKey != member.ownerPublicKey)
                Some("Client's public key does not match Member's public key")
              else
                None
          }
    }

  private def checkOwnerPublicKey(ownerPublicKey: PublicKey): Option[String] =
    try if (KeyFactory
              .getInstance("RSA")
              .generatePublic(new X509EncodedKeySpec(ownerPublicKey.value.toByteArray))
              .asInstanceOf[RSAPublicKey]
              .getModulus
              .bitLength != RequiredOwnerKeyLength)
      Some("Invalid owner public key length")
    else
      None
    catch {
      case _: InvalidKeySpecException =>
        Some("Invalid owner public key type")
    }

  private def checkTagAndMetadata(tag: Option[String], metadata: Option[JsObject]): Option[String] =
    tag
      .collect {
        case excessiveTag if excessiveTag.length > MaxStringLength =>
          s"Tag length exceeds maximum ($MaxStringLength): $excessiveTag"
      }
      .orElse(metadata.map(Json.stringify).collect {
        case excessiveMetadataString if excessiveMetadataString.length > MaxMetadataSize =>
          s"Metadata size exceeds maximum ($MaxMetadataSize): $excessiveMetadataString"
      })

  private def checkTransaction(from: AccountId,
                               to: AccountId,
                               value: BigDecimal,
                               zone: Zone,
                               balances: Map[AccountId, BigDecimal]): Option[String] =
    if (!zone.accounts.contains(from))
      Some(s"Invalid transaction source account: $from")
    else if (!zone.accounts.contains(to))
      Some(s"Invalid transaction destination account: $to")
    else if (to == from)
      Some(s"Invalid reflexive transaction (source: $from, destination: $to)")
    else {
      val updatedSourceBalance = balances(from) - value
      if (updatedSourceBalance < 0 && from != zone.equityAccountId)
        Some(s"Illegal transaction value: $value")
      else
        None
    }
}

class ZoneValidatorActor extends PersistentActor with ActorLogging with AtLeastOnceDelivery {

  import ShardRegion.Passivate
  import ZoneValidatorActor.PassivationCountdownActor._
  import context.dispatcher

  private[this] val mediator          = DistributedPubSub(context.system).mediator
  private[this] val publishStatusTick = context.system.scheduler.schedule(0.seconds, 30.seconds, self, PublishStatus)
  private[this] val passivationCountdownActor =
    context.actorOf(PassivationCountdownActor.props.withDeploy(Deploy.local))

  private[this] val zoneId = ZoneId(UUID.fromString(self.path.name))

  private[this] var state = State()

  private[this] var nextExpectedCommandSequenceNumbers = Map.empty[ActorPath, Long].withDefaultValue(1L)
  private[this] var messageSequenceNumbers             = Map.empty[ActorPath, Long].withDefaultValue(1L)
  private[this] var pendingDeliveries                  = Map.empty[ActorPath, Set[Long]].withDefaultValue(Set.empty)

  override def persistenceId: String = zoneId.persistenceId

  override def preStart(): Unit = {
    super.preStart()
    log.info("Started")
  }

  override def postStop(): Unit = {
    publishStatusTick.cancel()
    super.postStop()
    log.info("Stopped")
  }

  override def receiveCommand: Receive = waitForZone

  override def receiveRecover: Receive = {
    case event: Event =>
      updateState(event)
    case RecoveryCompleted =>
      state.clientConnections.keys.foreach(clientConnection =>
        context.actorSelection(clientConnection) ! ZoneRestarted(zoneId))
      state = state.copy(
        clientConnections = Map.empty
      )
  }

  private[this] def waitForZone: Receive =
    publishStatus orElse messageReceivedConfirmation orElse waitForTimeout orElse {
      case AuthenticatedCommandWithIds(_, command, correlationId, sequenceNumber, deliveryId) =>
        passivationCountdownActor ! CommandReceivedEvent
        exactlyOnce(sequenceNumber, deliveryId) {
          command match {
            case CreateZoneCommand(
                equityOwnerPublicKey,
                equityOwnerName,
                equityOwnerMetadata,
                equityAccountName,
                equityAccountMetadata,
                name,
                metadata
                ) =>
              checkTagAndMetadata(equityOwnerName, equityOwnerMetadata)
                .orElse(checkTagAndMetadata(equityAccountName, equityAccountMetadata))
                .orElse(checkTagAndMetadata(name, metadata)) match {
                case Some(error) =>
                  deliverErrorResponse(
                    JsonRpcResponseErrorMessage.applicationError(
                      code = JsonRpcResponseErrorMessage.ReservedErrorCodeFloor - 1,
                      message = error,
                      data = None,
                      correlationId
                    )
                  )
                case None =>
                  val equityOwner = Member(
                    MemberId(0),
                    equityOwnerPublicKey,
                    equityOwnerName,
                    equityOwnerMetadata
                  )
                  val equityAccount = Account(
                    AccountId(0),
                    Set(equityOwner.id),
                    equityAccountName,
                    equityAccountMetadata
                  )
                  val created = System.currentTimeMillis
                  val expires = created + ZoneLifetime.toMillis
                  val zone = Zone(
                    zoneId,
                    equityAccount.id,
                    Map(
                      equityOwner.id -> equityOwner
                    ),
                    Map(
                      equityAccount.id -> equityAccount
                    ),
                    Map.empty,
                    created,
                    expires,
                    name,
                    metadata
                  )
                  persist(ZoneCreatedEvent(created, zone)) { zoneCreatedEvent =>
                    updateState(zoneCreatedEvent)
                    deliverSuccessResponse(
                      CreateZoneResponse(
                        zoneCreatedEvent.zone
                      ),
                      correlationId
                    )
                    self ! PublishStatus
                  }
              }
            case _ =>
              deliverErrorResponse(
                JsonRpcResponseErrorMessage.applicationError(
                  code = JsonRpcResponseErrorMessage.ReservedErrorCodeFloor - 1,
                  message = "Zone does not exist",
                  data = None,
                  correlationId
                )
              )
          }
        }
    }

  private[this] def withZone: Receive =
    publishStatus orElse messageReceivedConfirmation orElse waitForTimeout orElse {
      case AuthenticatedCommandWithIds(publicKey, command, correlationId, sequenceNumber, deliveryId) =>
        passivationCountdownActor ! CommandReceivedEvent
        exactlyOnce(sequenceNumber, deliveryId)(
          handleZoneCommand(publicKey, command, correlationId)
        )
      case Terminated(clientConnection) =>
        handleQuit(clientConnection) {
          self ! PublishStatus
        }
        nextExpectedCommandSequenceNumbers = nextExpectedCommandSequenceNumbers - clientConnection.path
        messageSequenceNumbers = messageSequenceNumbers - clientConnection.path
        pendingDeliveries(clientConnection.path).foreach(confirmDelivery)
        pendingDeliveries = pendingDeliveries - clientConnection.path
    }

  private[this] def updateState(event: Event): Unit = {
    state = state.updated(event)
    if (event.isInstanceOf[ZoneCreatedEvent])
      context.become(withZone)
  }

  private[this] def publishStatus: Receive = {
    case PublishStatus =>
      if (state.zone != null)
        mediator ! Publish(
          Topic,
          ActiveZoneSummary(
            zoneId,
            state.zone.metadata,
            state.zone.members.values.toSet,
            state.zone.accounts.values.toSet,
            state.zone.transactions.values.toSet,
            state.clientConnections.values.toSet
          )
        )
  }

  private[this] def messageReceivedConfirmation: Receive = {
    case MessageReceivedConfirmation(deliveryId) =>
      confirmDelivery(deliveryId)
      pendingDeliveries = pendingDeliveries + (sender().path -> (pendingDeliveries(sender().path) - deliveryId))
      if (pendingDeliveries(sender().path).isEmpty)
        pendingDeliveries = pendingDeliveries - sender().path
  }

  private[this] def waitForTimeout: Receive = {
    case RequestPassivate =>
      context.parent ! Passivate(stopMessage = PoisonPill)
  }

  private[this] def exactlyOnce(sequenceNumber: Long, deliveryId: Long)(body: => Unit): Unit = {
    val nextExpectedCommandSequenceNumber = nextExpectedCommandSequenceNumbers(sender().path)
    if (sequenceNumber <= nextExpectedCommandSequenceNumber)
      sender() ! CommandReceivedConfirmation(zoneId, deliveryId)
    if (sequenceNumber == nextExpectedCommandSequenceNumber) {
      nextExpectedCommandSequenceNumbers = nextExpectedCommandSequenceNumbers + (sender().path -> (sequenceNumber + 1))
      body
    }
  }

  private[this] def handleZoneCommand(publicKey: PublicKey, command: Command, correlationId: CorrelationId): Unit =
    command match {
      case createZoneCommand: CreateZoneCommand =>
        val sequenceNumber = messageSequenceNumbers(sender().path)
        messageSequenceNumbers = messageSequenceNumbers + (sender().path -> (sequenceNumber + 1))
        deliver(sender().path) { deliveryId =>
          pendingDeliveries = pendingDeliveries + (sender().path -> (pendingDeliveries(sender().path) + deliveryId))
          ZoneAlreadyExists(createZoneCommand, correlationId, sequenceNumber, deliveryId)
        }
      case JoinZoneCommand(_) =>
        if (state.clientConnections.contains(sender().path))
          deliverErrorResponse(
            JsonRpcResponseErrorMessage.applicationError(
              code = JsonRpcResponseErrorMessage.ReservedErrorCodeFloor - 1,
              message = "Zone already joined",
              data = None,
              correlationId
            )
          )
        else
          handleJoin(sender(), publicKey) { state =>
            deliverSuccessResponse(
              JoinZoneResponse(
                state.zone,
                state.clientConnections.values.toSet
              ),
              correlationId
            )
            self ! PublishStatus
          }
      case QuitZoneCommand(_) =>
        if (!state.clientConnections.contains(sender().path))
          deliverErrorResponse(
            JsonRpcResponseErrorMessage.applicationError(
              code = JsonRpcResponseErrorMessage.ReservedErrorCodeFloor - 1,
              message = "Zone not joined",
              data = None,
              correlationId
            )
          )
        else
          handleQuit(sender()) {
            deliverSuccessResponse(
              QuitZoneResponse,
              correlationId
            )
            self ! PublishStatus
          }
      case ChangeZoneNameCommand(_, name) =>
        checkTagAndMetadata(name, None) match {
          case Some(error) =>
            deliverErrorResponse(
              JsonRpcResponseErrorMessage.applicationError(
                code = JsonRpcResponseErrorMessage.ReservedErrorCodeFloor - 1,
                message = error,
                data = None,
                correlationId
              )
            )
          case None =>
            persist(ZoneNameChangedEvent(System.currentTimeMillis, name)) { zoneNameChangedEvent =>
              updateState(zoneNameChangedEvent)
              deliverSuccessResponse(
                ChangeZoneNameResponse,
                correlationId
              )
              self ! PublishStatus
              deliverNotification(
                ZoneNameChangedNotification(
                  zoneId,
                  zoneNameChangedEvent.name
                )
              )
            }
        }
      case CreateMemberCommand(_, ownerPublicKey, name, metadata) =>
        checkOwnerPublicKey(ownerPublicKey).orElse(checkTagAndMetadata(name, metadata)) match {
          case Some(error) =>
            deliverErrorResponse(
              JsonRpcResponseErrorMessage.applicationError(
                code = JsonRpcResponseErrorMessage.ReservedErrorCodeFloor - 1,
                message = error,
                data = None,
                correlationId
              )
            )
          case None =>
            val member = Member(
              MemberId(state.zone.members.size),
              ownerPublicKey,
              name,
              metadata
            )
            persist(MemberCreatedEvent(System.currentTimeMillis, member)) { memberCreatedEvent =>
              updateState(memberCreatedEvent)
              deliverSuccessResponse(
                CreateMemberResponse(
                  memberCreatedEvent.member
                ),
                correlationId
              )
              self ! PublishStatus
              deliverNotification(
                MemberCreatedNotification(
                  zoneId,
                  memberCreatedEvent.member
                )
              )
            }
        }
      case UpdateMemberCommand(_, member) =>
        checkCanModify(state.zone, member.id, publicKey)
          .orElse(checkOwnerPublicKey(member.ownerPublicKey))
          .orElse(checkTagAndMetadata(member.name, member.metadata)) match {
          case Some(error) =>
            deliverErrorResponse(
              JsonRpcResponseErrorMessage.applicationError(
                code = JsonRpcResponseErrorMessage.ReservedErrorCodeFloor - 1,
                message = error,
                data = None,
                correlationId
              )
            )
          case None =>
            persist(MemberUpdatedEvent(System.currentTimeMillis, member)) { memberUpdatedEvent =>
              updateState(memberUpdatedEvent)
              deliverSuccessResponse(
                UpdateMemberResponse,
                correlationId
              )
              self ! PublishStatus
              deliverNotification(
                MemberUpdatedNotification(
                  zoneId,
                  memberUpdatedEvent.member
                )
              )
            }
        }
      case CreateAccountCommand(_, owners, name, metadata) =>
        checkAccountOwners(state.zone, owners).orElse(checkTagAndMetadata(name, metadata)) match {
          case Some(error) =>
            deliverErrorResponse(
              JsonRpcResponseErrorMessage.applicationError(
                code = JsonRpcResponseErrorMessage.ReservedErrorCodeFloor - 1,
                message = error,
                data = None,
                correlationId
              )
            )
          case None =>
            val account = Account(
              AccountId(state.zone.accounts.size),
              owners,
              name,
              metadata
            )
            persist(AccountCreatedEvent(System.currentTimeMillis, account)) { accountCreatedEvent =>
              updateState(accountCreatedEvent)
              deliverSuccessResponse(
                CreateAccountResponse(
                  accountCreatedEvent.account
                ),
                correlationId
              )
              self ! PublishStatus
              deliverNotification(
                AccountCreatedNotification(
                  zoneId,
                  accountCreatedEvent.account
                )
              )
            }
        }
      case UpdateAccountCommand(_, account) =>
        checkCanModify(state.zone, account.id, publicKey).orElse(
          checkAccountOwners(state.zone, account.ownerMemberIds)
            .orElse(checkTagAndMetadata(account.name, account.metadata))) match {
          case Some(error) =>
            deliverErrorResponse(
              JsonRpcResponseErrorMessage.applicationError(
                code = JsonRpcResponseErrorMessage.ReservedErrorCodeFloor - 1,
                message = error,
                data = None,
                correlationId
              )
            )
          case None =>
            persist(AccountUpdatedEvent(System.currentTimeMillis, account)) { accountUpdatedEvent =>
              updateState(accountUpdatedEvent)
              deliverSuccessResponse(
                UpdateAccountResponse,
                correlationId
              )
              self ! PublishStatus
              deliverNotification(
                AccountUpdatedNotification(
                  zoneId,
                  accountUpdatedEvent.account
                )
              )
            }
        }
      case AddTransactionCommand(_, actingAs, from, to, value, description, metadata) =>
        checkCanModify(state.zone, from, actingAs, publicKey).orElse(
          checkTransaction(from, to, value, state.zone, state.balances)
            .orElse(checkTagAndMetadata(description, metadata))) match {
          case Some(error) =>
            deliverErrorResponse(
              JsonRpcResponseErrorMessage.applicationError(
                code = JsonRpcResponseErrorMessage.ReservedErrorCodeFloor - 1,
                message = error,
                data = None,
                correlationId
              )
            )
          case None =>
            val created = System.currentTimeMillis
            val transaction = Transaction(
              TransactionId(state.zone.transactions.size),
              from,
              to,
              value,
              actingAs,
              created,
              description,
              metadata
            )
            persist(TransactionAddedEvent(created, transaction)) { transactionAddedEvent =>
              updateState(transactionAddedEvent)
              deliverSuccessResponse(
                AddTransactionResponse(
                  transactionAddedEvent.transaction
                ),
                correlationId
              )
              self ! PublishStatus
              deliverNotification(
                TransactionAddedNotification(
                  zoneId,
                  transactionAddedEvent.transaction
                )
              )
            }
        }
    }

  private[this] def handleJoin(clientConnection: ActorRef, publicKey: PublicKey)(onStateUpdate: State => Unit): Unit =
    persist(
      ZoneJoinedEvent(System.currentTimeMillis, clientConnection.path, publicKey)
    ) { zoneJoinedEvent =>
      if (state.clientConnections.isEmpty)
        passivationCountdownActor ! Stop
      context.watch(clientConnection)
      val wasAlreadyPresent = state.clientConnections.values.exists(_ == zoneJoinedEvent.publicKey)
      updateState(zoneJoinedEvent)
      onStateUpdate(state)
      if (!wasAlreadyPresent)
        deliverNotification(ClientJoinedZoneNotification(zoneId, zoneJoinedEvent.publicKey))
    }

  private[this] def handleQuit(clientConnection: ActorRef)(onStateUpdate: => Unit): Unit =
    persist(
      ZoneQuitEvent(System.currentTimeMillis, clientConnection.path)
    ) { zoneQuitEvent =>
      val publicKey = state.clientConnections(clientConnection.path)
      updateState(zoneQuitEvent)
      onStateUpdate
      val isStillPresent = state.clientConnections.values.exists(_ == publicKey)
      if (!isStillPresent)
        deliverNotification(ClientQuitZoneNotification(zoneId, publicKey))
      context.unwatch(clientConnection)
      if (state.clientConnections.isEmpty)
        passivationCountdownActor ! Start
    }

  private[this] def deliverErrorResponse(response: JsonRpcResponseErrorMessage): Unit =
    deliverResponse {
      case (sequenceNumber, deliveryId) =>
        ErrorResponseWithIds(
          response,
          sequenceNumber,
          deliveryId
        )
    }

  private[this] def deliverSuccessResponse(response: Response, correlationId: CorrelationId): Unit =
    deliverResponse {
      case (sequenceNumber, deliveryId) =>
        SuccessResponseWithIds(
          response,
          correlationId,
          sequenceNumber,
          deliveryId
        )
    }

  private[this] def deliverResponse(sequenceNumberAndDeliveryIdToMessage: (Long, Long) => Any): Unit = {
    val sequenceNumber = messageSequenceNumbers(sender().path)
    messageSequenceNumbers = messageSequenceNumbers + (sender().path -> (sequenceNumber + 1))
    deliver(sender().path) { deliveryId =>
      pendingDeliveries = pendingDeliveries + (sender().path -> (pendingDeliveries(sender().path) + deliveryId))
      sequenceNumberAndDeliveryIdToMessage(sequenceNumber, deliveryId)
    }
  }

  private[this] def deliverNotification(notification: Notification): Unit = {
    state.clientConnections.keys.foreach { clientConnection =>
      val sequenceNumber = messageSequenceNumbers(clientConnection)
      messageSequenceNumbers = messageSequenceNumbers + (clientConnection -> (sequenceNumber + 1))
      deliver(clientConnection) { deliveryId =>
        pendingDeliveries = pendingDeliveries + (sender().path -> (pendingDeliveries(clientConnection) + deliveryId))
        NotificationWithIds(notification, sequenceNumber, deliveryId)
      }
    }
  }
}