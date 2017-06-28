package com.dhpcs.liquidity.server.actor

import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.{InvalidKeySpecException, X509EncodedKeySpec}
import java.util.UUID

import akka.actor._
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Publish
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.Passivate
import akka.persistence.{AtLeastOnceDelivery, PersistentActor, RecoveryCompleted}
import com.dhpcs.liquidity.actor.protocol._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.persistence._
import com.dhpcs.liquidity.server.actor.ZoneValidatorActor._

import scala.concurrent.duration._

object ZoneValidatorActor {

  def props: Props = Props[ZoneValidatorActor]

  final val ShardTypeName = "zone-validator"

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case envelopedZoneCommand: EnvelopedZoneCommand => (envelopedZoneCommand.zoneId.id.toString, envelopedZoneCommand)
  }

  private val NumberOfShards = 10

  val extractShardId: ShardRegion.ExtractShardId = {
    case EnvelopedZoneCommand(zoneId, _, _, _, _, _) => (math.abs(zoneId.id.hashCode) % NumberOfShards).toString
  }

  private final val RequiredOwnerKeySize = 2048

  private val ZoneLifetime = 2.days

  private final case class State(zone: Zone = null,
                                 balances: Map[AccountId, BigDecimal] = Map.empty.withDefaultValue(BigDecimal(0)),
                                 clientConnections: Map[ActorPath, PublicKey] = Map.empty[ActorPath, PublicKey]) {

    def updated(zoneEvent: ZoneEvent): State = zoneEvent match {
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

    def props: Props = Props[PassivationCountdownActor]

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
              .bitLength != RequiredOwnerKeySize)
      Some("Invalid owner public key length")
    else
      None
    catch {
      case _: InvalidKeySpecException =>
        Some("Invalid owner public key type")
    }

  private def checkTagAndMetadata(tag: Option[String],
                                  metadata: Option[com.google.protobuf.struct.Struct]): Option[String] =
    tag
      .collect {
        case excessiveTag if excessiveTag.length > MaxStringLength =>
          s"Tag length exceeds maximum ($MaxStringLength): $excessiveTag"
      }
      .orElse(metadata.collect {
        case excessiveMetadata if excessiveMetadata.toByteArray.length > MaxMetadataSize =>
          s"Metadata size exceeds maximum ($MaxMetadataSize): $excessiveMetadata"
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
    else if (value.compare(0) == -1)
      Some(s"Invalid transaction value ($value)")
    else {
      val updatedSourceBalance = balances(from) - value
      if (updatedSourceBalance < 0 && from != zone.equityAccountId)
        Some(s"Illegal transaction value: $value")
      else
        None
    }
}

class ZoneValidatorActor extends PersistentActor with ActorLogging with AtLeastOnceDelivery {

  import ZoneValidatorActor.PassivationCountdownActor._
  import context.dispatcher

  private[this] val mediator          = DistributedPubSub(context.system).mediator
  private[this] val publishStatusTick = context.system.scheduler.schedule(0.seconds, 30.seconds, self, PublishStatus)
  private[this] val passivationCountdownActor =
    context.actorOf(PassivationCountdownActor.props)

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
    case zoneEvent: ZoneEvent =>
      updateState(zoneEvent)
    case RecoveryCompleted =>
      state.clientConnections.keys.foreach(clientConnection =>
        context.actorSelection(clientConnection) ! ZoneRestarted(zoneId))
      state = state.copy(
        clientConnections = Map.empty
      )
  }

  private[this] def waitForZone: Receive =
    publishStatus orElse messageReceivedConfirmation orElse waitForTimeout orElse {
      case EnvelopedZoneCommand(_, command, _, correlationId, sequenceNumber, deliveryId) =>
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
                  deliverResponse(ErrorResponse(error), correlationId)
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
                    deliverResponse(
                      CreateZoneResponse(
                        zoneCreatedEvent.zone
                      ),
                      correlationId
                    )
                    self ! PublishStatus
                  }
              }
            case _ =>
              deliverResponse(ErrorResponse("Zone does not exist"), correlationId)
          }
        }
    }

  private[this] def withZone: Receive =
    publishStatus orElse messageReceivedConfirmation orElse waitForTimeout orElse {
      case EnvelopedZoneCommand(_, command, publicKey, correlationId, sequenceNumber, deliveryId) =>
        passivationCountdownActor ! CommandReceivedEvent
        exactlyOnce(sequenceNumber, deliveryId)(
          handleCommand(publicKey, command, correlationId)
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

  private[this] def updateState(zoneEvent: ZoneEvent): Unit = {
    state = state.updated(zoneEvent)
    if (zoneEvent.isInstanceOf[ZoneCreatedEvent])
      context.become(withZone)
  }

  private[this] def publishStatus: Receive = {
    case PublishStatus =>
      if (state.zone != null)
        mediator ! Publish(
          ZoneStatusTopic,
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
      sender() ! ZoneCommandReceivedConfirmation(zoneId, deliveryId)
    if (sequenceNumber == nextExpectedCommandSequenceNumber) {
      nextExpectedCommandSequenceNumbers = nextExpectedCommandSequenceNumbers + (sender().path -> (sequenceNumber + 1))
      body
    }
  }

  private[this] def handleCommand(publicKey: PublicKey, command: ZoneCommand, correlationId: Long): Unit =
    command match {
      case EmptyZoneCommand =>
      case createZoneCommand: CreateZoneCommand =>
        val sequenceNumber = messageSequenceNumbers(sender().path)
        messageSequenceNumbers = messageSequenceNumbers + (sender().path -> (sequenceNumber + 1))
        deliver(sender().path) { deliveryId =>
          pendingDeliveries = pendingDeliveries + (sender().path -> (pendingDeliveries(sender().path) + deliveryId))
          ZoneAlreadyExists(createZoneCommand, correlationId, sequenceNumber, deliveryId)
        }
      case JoinZoneCommand =>
        if (state.clientConnections.contains(sender().path))
          deliverResponse(ErrorResponse("Zone already joined"), correlationId)
        else
          handleJoin(sender(), publicKey) { state =>
            deliverResponse(
              JoinZoneResponse(
                state.zone,
                state.clientConnections.values.toSet
              ),
              correlationId
            )
            self ! PublishStatus
          }
      case QuitZoneCommand =>
        if (!state.clientConnections.contains(sender().path))
          deliverResponse(ErrorResponse("Zone not joined"), correlationId)
        else
          handleQuit(sender()) {
            deliverResponse(
              QuitZoneResponse,
              correlationId
            )
            self ! PublishStatus
          }
      case ChangeZoneNameCommand(name) =>
        checkTagAndMetadata(name, None) match {
          case Some(error) =>
            deliverResponse(ErrorResponse(error), correlationId)
          case None =>
            persist(ZoneNameChangedEvent(System.currentTimeMillis, name)) { zoneNameChangedEvent =>
              updateState(zoneNameChangedEvent)
              deliverResponse(
                ChangeZoneNameResponse,
                correlationId
              )
              self ! PublishStatus
              deliverNotification(
                ZoneNameChangedNotification(
                  zoneNameChangedEvent.name
                )
              )
            }
        }
      case CreateMemberCommand(ownerPublicKey, name, metadata) =>
        checkOwnerPublicKey(ownerPublicKey).orElse(checkTagAndMetadata(name, metadata)) match {
          case Some(error) =>
            deliverResponse(ErrorResponse(error), correlationId)
          case None =>
            val member = Member(
              MemberId(state.zone.members.size.toLong),
              ownerPublicKey,
              name,
              metadata
            )
            persist(MemberCreatedEvent(System.currentTimeMillis, member)) { memberCreatedEvent =>
              updateState(memberCreatedEvent)
              deliverResponse(
                CreateMemberResponse(
                  memberCreatedEvent.member
                ),
                correlationId
              )
              self ! PublishStatus
              deliverNotification(
                MemberCreatedNotification(
                  memberCreatedEvent.member
                )
              )
            }
        }
      case UpdateMemberCommand(member) =>
        checkCanModify(state.zone, member.id, publicKey)
          .orElse(checkOwnerPublicKey(member.ownerPublicKey))
          .orElse(checkTagAndMetadata(member.name, member.metadata)) match {
          case Some(error) =>
            deliverResponse(ErrorResponse(error), correlationId)
          case None =>
            persist(MemberUpdatedEvent(System.currentTimeMillis, member)) { memberUpdatedEvent =>
              updateState(memberUpdatedEvent)
              deliverResponse(
                UpdateMemberResponse,
                correlationId
              )
              self ! PublishStatus
              deliverNotification(
                MemberUpdatedNotification(
                  memberUpdatedEvent.member
                )
              )
            }
        }
      case CreateAccountCommand(owners, name, metadata) =>
        checkAccountOwners(state.zone, owners).orElse(checkTagAndMetadata(name, metadata)) match {
          case Some(error) =>
            deliverResponse(ErrorResponse(error), correlationId)
          case None =>
            val account = Account(
              AccountId(state.zone.accounts.size.toLong),
              owners,
              name,
              metadata
            )
            persist(AccountCreatedEvent(System.currentTimeMillis, account)) { accountCreatedEvent =>
              updateState(accountCreatedEvent)
              deliverResponse(
                CreateAccountResponse(
                  accountCreatedEvent.account
                ),
                correlationId
              )
              self ! PublishStatus
              deliverNotification(
                AccountCreatedNotification(
                  accountCreatedEvent.account
                )
              )
            }
        }
      case UpdateAccountCommand(account) =>
        checkCanModify(state.zone, account.id, publicKey).orElse(
          checkAccountOwners(state.zone, account.ownerMemberIds)
            .orElse(checkTagAndMetadata(account.name, account.metadata))) match {
          case Some(error) =>
            deliverResponse(ErrorResponse(error), correlationId)
          case None =>
            persist(AccountUpdatedEvent(System.currentTimeMillis, account)) { accountUpdatedEvent =>
              updateState(accountUpdatedEvent)
              deliverResponse(
                UpdateAccountResponse,
                correlationId
              )
              self ! PublishStatus
              deliverNotification(
                AccountUpdatedNotification(
                  accountUpdatedEvent.account
                )
              )
            }
        }
      case AddTransactionCommand(actingAs, from, to, value, description, metadata) =>
        checkCanModify(state.zone, from, actingAs, publicKey).orElse(
          checkTransaction(from, to, value, state.zone, state.balances)
            .orElse(checkTagAndMetadata(description, metadata))) match {
          case Some(error) =>
            deliverResponse(ErrorResponse(error), correlationId)
          case None =>
            val created = System.currentTimeMillis
            val transaction = Transaction(
              TransactionId(state.zone.transactions.size.toLong),
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
              deliverResponse(
                AddTransactionResponse(
                  transactionAddedEvent.transaction
                ),
                correlationId
              )
              self ! PublishStatus
              deliverNotification(
                TransactionAddedNotification(
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
        deliverNotification(ClientJoinedZoneNotification(zoneJoinedEvent.publicKey))
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
        deliverNotification(ClientQuitZoneNotification(publicKey))
      context.unwatch(clientConnection)
      if (state.clientConnections.isEmpty)
        passivationCountdownActor ! Start
    }

  private[this] def deliverResponse(response: ZoneResponse, correlationId: Long): Unit =
    deliverResponse {
      case (sequenceNumber, deliveryId) =>
        EnvelopedZoneResponse(
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

  private[this] def deliverNotification(notification: ZoneNotification): Unit = {
    state.clientConnections.keys.foreach { clientConnection =>
      val sequenceNumber = messageSequenceNumbers(clientConnection)
      messageSequenceNumbers = messageSequenceNumbers + (clientConnection -> (sequenceNumber + 1))
      deliver(clientConnection) { deliveryId =>
        pendingDeliveries = pendingDeliveries + (sender().path -> (pendingDeliveries(clientConnection) + deliveryId))
        EnvelopedZoneNotification(zoneId, notification, sequenceNumber, deliveryId)
      }
    }
  }
}
