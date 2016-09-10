package com.dhpcs.liquidity.server.actors

import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.{InvalidKeySpecException, X509EncodedKeySpec}
import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorPath, ActorRef, PoisonPill, Props, ReceiveTimeout, Terminated}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Publish
import akka.cluster.sharding.ShardRegion
import akka.persistence.{AtLeastOnceDelivery, PersistentActor, RecoveryCompleted}
import com.dhpcs.jsonrpc.JsonRpcResponseError
import com.dhpcs.jsonrpc.ResponseCompanion.ErrorResponse
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.protocol._
import com.dhpcs.liquidity.server.actors.ZoneValidatorActor._
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.duration._

object ZoneValidatorActor {
  def props: Props = Props(new ZoneValidatorActor)

  final val ShardName = "ZoneValidator"

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case EnvelopedMessage(zoneId, message) =>
      (zoneId.id.toString, message)
    case authenticatedCommandWithIds@AuthenticatedCommandWithIds(_, zoneCommand: ZoneCommand, _, _, _) =>
      (zoneCommand.zoneId.id.toString, authenticatedCommandWithIds)
  }

  private val NumberOfShards = 10

  val extractShardId: ShardRegion.ExtractShardId = {
    case EnvelopedMessage(zoneId, _) =>
      (math.abs(zoneId.id.hashCode) % NumberOfShards).toString
    case AuthenticatedCommandWithIds(_, zoneCommand: ZoneCommand, _, _, _) =>
      (math.abs(zoneCommand.zoneId.id.hashCode) % NumberOfShards).toString
  }

  final val Topic = "Zone"

  private final val RequiredOwnerKeyLength = 2048

  case class EnvelopedMessage(zoneId: ZoneId, message: Any)

  case class AuthenticatedCommandWithIds(publicKey: PublicKey,
                                         command: Command,
                                         correlationId: Option[Either[String, BigDecimal]],
                                         sequenceNumber: Long,
                                         deliveryId: Long)

  case class CommandReceivedConfirmation(zoneId: ZoneId, deliveryId: Long)

  case class ZoneAlreadyExists(createZoneCommand: CreateZoneCommand,
                               correlationId: Option[Either[String, BigDecimal]],
                               sequenceNumber: Long,
                               deliveryId: Long)

  case class ZoneRestarted(zoneId: ZoneId)

  case class ResponseWithIds(response: Either[ErrorResponse, ResultResponse],
                             correlationId: Option[Either[String, BigDecimal]],
                             sequenceNumber: Long,
                             deliveryId: Long)

  case class NotificationWithIds(notification: Notification, sequenceNumber: Long, deliveryId: Long)

  case class ActiveZoneSummary(zoneId: ZoneId,
                               metadata: Option[JsObject],
                               members: Set[Member],
                               accounts: Set[Account],
                               transactions: Set[Transaction],
                               clientConnections: Set[PublicKey])

  private val ZoneLifetime = 2.days

  private case class State(zone: Zone = null,
                           balances: Map[AccountId, BigDecimal] = Map.empty.withDefaultValue(BigDecimal(0)),
                           clientConnections: Map[ActorPath, PublicKey] = Map.empty[ActorPath, PublicKey]) {

    def updated(event: Event) = event match {
      case zoneCreatedEvent: ZoneCreatedEvent =>
        copy(
          zone = zoneCreatedEvent.zone
        )
      case ZoneJoinedEvent(timestamp, clientConnectionActorPath, publicKey) =>
        copy(
          clientConnections = clientConnections + (ActorPath.fromString(clientConnectionActorPath) -> publicKey)
        )
      case ZoneQuitEvent(timestamp, clientConnectionActorPath) =>
        copy(
          clientConnections = clientConnections - ActorPath.fromString(clientConnectionActorPath)
        )
      case ZoneNameChangedEvent(timestamp, name) =>
        copy(
          zone = zone.copy(
            name = name
          )
        )
      case MemberCreatedEvent(timestamp, member) =>
        copy(
          zone = zone.copy(
            members = zone.members + (member.id -> member)
          )
        )
      case MemberUpdatedEvent(timestamp, member) =>
        copy(
          zone = zone.copy(
            members = zone.members + (member.id -> member)
          )
        )
      case AccountCreatedEvent(timestamp, account) =>
        copy(
          zone = zone.copy(
            accounts = zone.accounts + (account.id -> account)
          )
        )
      case AccountUpdatedEvent(timestamp, account) =>
        copy(
          zone = zone.copy(
            accounts = zone.accounts + (account.id -> account)
          )
        )
      case TransactionAddedEvent(timestamp, transaction) =>
        val updatedSourceBalance = balances(transaction.from) - transaction.value
        val updatedDestinationBalance = balances(transaction.to) + transaction.value
        copy(
          balances = balances +
            (transaction.from -> updatedSourceBalance) +
            (transaction.to -> updatedDestinationBalance),
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
      case ReceiveTimeout =>
        context.parent ! RequestPassivate
      case CommandReceivedEvent =>
      case Start =>
        context.setReceiveTimeout(PassivationTimeout)
      case Stop =>
        context.setReceiveTimeout(Duration.Undefined)
    }
  }

  private def checkAccountOwners(zone: Zone, owners: Set[MemberId]): Option[String] = {
    val invalidAccountOwners = owners -- zone.members.keys
    if (invalidAccountOwners.nonEmpty) {
      Some(s"Invalid account owners: $invalidAccountOwners")
    } else {
      None
    }
  }

  private def checkCanModify(zone: Zone, memberId: MemberId, publicKey: PublicKey): Option[String] =
    zone.members.get(memberId).fold[Option[String]](ifEmpty = Some("Member does not exist"))(member =>
      if (publicKey != member.ownerPublicKey) {
        Some("Client's public key does not match Member's public key")
      } else {
        None
      }
    )

  private def checkCanModify(zone: Zone, accountId: AccountId, publicKey: PublicKey): Option[String] =
    zone.accounts.get(accountId).fold[Option[String]](ifEmpty = Some("Account does not exist"))(account =>
      if (!account.ownerMemberIds.exists(memberId =>
        zone.members.get(memberId).fold(ifEmpty = false)(publicKey == _.ownerPublicKey)
      )) {
        Some("Client's public key does not match that of any account owner member")
      } else {
        None
      }
    )

  private def checkCanModify(zone: Zone,
                             accountId: AccountId,
                             actingAs: MemberId,
                             publicKey: PublicKey): Option[String] =
    zone.accounts.get(accountId).fold[Option[String]](ifEmpty = Some("Account does not exist"))(account =>
      if (!account.ownerMemberIds.contains(actingAs)) {
        Some("Member is not an account owner")
      } else {
        zone.members.get(actingAs).fold[Option[String]](ifEmpty = Some("Member does not exist"))(member =>
          if (publicKey != member.ownerPublicKey) {
            Some("Client's public key does not match Member's public key")
          } else {
            None
          }
        )
      }
    )

  private def checkOwnerPublicKey(ownerPublicKey: PublicKey): Option[String] =
    try {
      if (KeyFactory.getInstance("RSA")
        .generatePublic(new X509EncodedKeySpec(ownerPublicKey.value))
        .asInstanceOf[RSAPublicKey].getModulus.bitLength != RequiredOwnerKeyLength) {
        Some("Invalid owner public key length")
      } else {
        None
      }
    } catch {
      case _: InvalidKeySpecException =>
        Some("Invalid owner public key type")
    }

  private def checkTagAndMetadata(tag: Option[String], metadata: Option[JsObject]): Option[String] =
    tag.collect {
      case excessiveTag if excessiveTag.length > MaxStringLength =>
        s"Tag length exceeds maximum ($MaxStringLength): $excessiveTag"
    }.orElse(metadata.map(Json.stringify).collect {
      case excessiveMetadataString if excessiveMetadataString.length > MaxMetadataSize =>
        s"Metadata size exceeds maximum ($MaxMetadataSize): $excessiveMetadataString"
    })

  private def checkTransaction(from: AccountId,
                               to: AccountId,
                               value: BigDecimal,
                               zone: Zone,
                               balances: Map[AccountId, BigDecimal]): Option[String] =
    if (!zone.accounts.contains(from)) {
      Some(s"Invalid transaction source account: $from")
    } else if (!zone.accounts.contains(to)) {
      Some(s"Invalid transaction destination account: $to")
    } else if (to == from) {
      Some(s"Invalid reflexive transaction (source: $from, destination: $to)")
    } else {
      val updatedSourceBalance = balances(from) - value
      if (updatedSourceBalance < 0 && from != zone.equityAccountId) {
        Some(s"Illegal transaction value: $value")
      } else {
        None
      }
    }
}

class ZoneValidatorActor extends PersistentActor with ActorLogging with AtLeastOnceDelivery {

  import ShardRegion.Passivate
  import ZoneValidatorActor.PassivationCountdownActor._
  import context.dispatcher

  private[this] val mediator = DistributedPubSub(context.system).mediator
  private[this] val publishStatusTick = context.system.scheduler.schedule(0.seconds, 30.seconds, self, PublishStatus)
  private[this] val passivationCountdownActor = context.actorOf(PassivationCountdownActor.props)

  private[this] val zoneId = ZoneId(UUID.fromString(self.path.name))

  private[this] var state = State()

  private[this] var nextExpectedCommandSequenceNumbers = Map.empty[ActorPath, Long].withDefaultValue(0L)
  private[this] var messageSequenceNumbers = Map.empty[ActorPath, Long].withDefaultValue(0L)
  private[this] var pendingDeliveries = Map.empty[ActorPath, Set[Long]].withDefaultValue(Set.empty)

  override def persistenceId: String = zoneId.toString

  override def preStart(): Unit = {
    super.preStart()
    log.info(s"Started actor for ${zoneId.id}")
  }

  override def postStop(): Unit = {
    publishStatusTick.cancel()
    super.postStop()
    log.info(s"Stopped actor for ${zoneId.id}")
  }

  override def receiveCommand: Receive = waitForZone

  override def receiveRecover: Receive = {
    case event: Event =>
      updateState(event)
    case RecoveryCompleted =>
      state.clientConnections.keys.foreach(clientConnection =>
        context.actorSelection(clientConnection) ! ZoneRestarted(zoneId)
      )
      state = state.copy(
        clientConnections = Map.empty
      )
      self ! PublishStatus
  }

  private[this] def waitForZone: Receive =
    publishStatus orElse messageReceivedConfirmation orElse waitForTimeout orElse {
      case AuthenticatedCommandWithIds(publicKey, command, correlationId, sequenceNumber, deliveryId) =>
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
                  deliverResponse(
                    Left(
                      ErrorResponse(
                        JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                        error
                      )
                    ),
                    correlationId
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
                    deliverResponse(
                      Right(
                        CreateZoneResponse(
                          zoneCreatedEvent.zone
                        )
                      ),
                      correlationId
                    )
                    self ! PublishStatus
                  }
              }
            case _ =>
              deliverResponse(
                Left(
                  ErrorResponse(
                    JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                    "Zone does not exist"
                  )
                ),
                correlationId
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
    if (event.isInstanceOf[ZoneCreatedEvent]) {
      context.become(withZone)
    }
  }

  private[this] def publishStatus: Receive = {
    case PublishStatus =>
      if (state.zone != null) {
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
  }

  private[this] def messageReceivedConfirmation: Receive = {
    case ClientConnectionActor.MessageReceivedConfirmation(deliveryId) =>
      confirmDelivery(deliveryId)
      pendingDeliveries = pendingDeliveries + (sender().path -> (pendingDeliveries(sender().path) - deliveryId))
      if (pendingDeliveries(sender().path).isEmpty) {
        pendingDeliveries = pendingDeliveries - sender().path
      }
  }

  private[this] def waitForTimeout: Receive = {
    case RequestPassivate =>
      context.parent ! Passivate(stopMessage = PoisonPill)
  }

  private[this] def exactlyOnce(sequenceNumber: Long, deliveryId: Long)(body: => Unit): Unit = {
    val nextExpectedCommandSequenceNumber = nextExpectedCommandSequenceNumbers(sender().path)
    if (sequenceNumber <= nextExpectedCommandSequenceNumber) {
      sender() ! CommandReceivedConfirmation(zoneId, deliveryId)
    }
    if (sequenceNumber == nextExpectedCommandSequenceNumber) {
      nextExpectedCommandSequenceNumbers = nextExpectedCommandSequenceNumbers + (sender().path -> (sequenceNumber + 1))
      body
    }
  }

  private[this] def handleZoneCommand(publicKey: PublicKey,
                                      command: Command,
                                      correlationId: Option[Either[String, BigDecimal]]): Unit = {
    command match {
      case createZoneCommand: CreateZoneCommand =>
        val sequenceNumber = messageSequenceNumbers(sender().path)
        messageSequenceNumbers = messageSequenceNumbers + (sender().path -> (sequenceNumber + 1))
        deliver(sender().path) { deliveryId =>
          pendingDeliveries = pendingDeliveries + (sender().path -> (pendingDeliveries(sender().path) + deliveryId))
          ZoneAlreadyExists(createZoneCommand, correlationId, sequenceNumber, deliveryId)
        }
      case JoinZoneCommand(_) =>
        if (state.clientConnections.contains(sender().path)) {
          deliverResponse(
            Left(
              ErrorResponse(
                JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                "Zone already joined"
              )
            ),
            correlationId
          )
        } else {
          handleJoin(sender(), publicKey) { state =>
            deliverResponse(
              Right(
                JoinZoneResponse(
                  state.zone,
                  state.clientConnections.values.toSet
                )
              ),
              correlationId
            )
            self ! PublishStatus
          }
        }
      case QuitZoneCommand(_) =>
        if (!state.clientConnections.contains(sender().path)) {
          deliverResponse(
            Left(
              ErrorResponse(
                JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                "Zone not joined"
              )
            ),
            correlationId
          )
        } else {
          handleQuit(sender()) {
            deliverResponse(
              Right(
                QuitZoneResponse
              ),
              correlationId
            )
            self ! PublishStatus
          }
        }
      case ChangeZoneNameCommand(_, name) =>
        checkTagAndMetadata(name, None) match {
          case Some(error) =>
            deliverResponse(
              Left(
                ErrorResponse(
                  JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                  error
                )
              ),
              correlationId
            )
          case None =>
            persist(ZoneNameChangedEvent(System.currentTimeMillis, name)) { zoneNameChangedEvent =>
              updateState(zoneNameChangedEvent)
              deliverResponse(
                Right(
                  ChangeZoneNameResponse
                ),
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
        checkOwnerPublicKey(ownerPublicKey)
          .orElse(checkTagAndMetadata(name, metadata)) match {
          case Some(error) =>
            deliverResponse(
              Left(
                ErrorResponse(
                  JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                  error
                )
              ),
              correlationId
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
              deliverResponse(
                Right(
                  CreateMemberResponse(
                    memberCreatedEvent.member
                  )
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
            deliverResponse(
              Left(
                ErrorResponse(
                  JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                  error
                )
              ),
              correlationId
            )
          case None =>
            persist(MemberUpdatedEvent(System.currentTimeMillis, member)) { memberUpdatedEvent =>
              updateState(memberUpdatedEvent)
              deliverResponse(
                Right(
                  UpdateMemberResponse
                ),
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
        checkAccountOwners(state.zone, owners)
          .orElse(checkTagAndMetadata(name, metadata)) match {
          case Some(error) =>
            deliverResponse(
              Left(
                ErrorResponse(
                  JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                  error
                )
              ),
              correlationId
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
              deliverResponse(
                Right(
                  CreateAccountResponse(
                    accountCreatedEvent.account
                  )
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
        checkCanModify(state.zone, account.id, publicKey)
          .orElse(checkAccountOwners(state.zone, account.ownerMemberIds)
            .orElse(checkTagAndMetadata(account.name, account.metadata))) match {
          case Some(error) =>
            deliverResponse(
              Left(
                ErrorResponse(
                  JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                  error
                )
              ),
              correlationId
            )
          case None =>
            persist(AccountUpdatedEvent(System.currentTimeMillis, account)) { accountUpdatedEvent =>
              updateState(accountUpdatedEvent)
              deliverResponse(
                Right(
                  UpdateAccountResponse
                ),
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
        checkCanModify(state.zone, from, actingAs, publicKey)
          .orElse(checkTransaction(from, to, value, state.zone, state.balances)
            .orElse(checkTagAndMetadata(description, metadata))) match {
          case Some(error) =>
            deliverResponse(
              Left(
                ErrorResponse(
                  JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                  error
                )
              ),
              correlationId
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
              deliverResponse(
                Right(
                  AddTransactionResponse(
                    transactionAddedEvent.transaction
                  )
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
  }

  private[this] def handleJoin(clientConnection: ActorRef,
                               publicKey: PublicKey)
                              (onStateUpdate: State => Unit = _ => ()): Unit =
    persist(
      ZoneJoinedEvent(System.currentTimeMillis, clientConnection.path.toSerializationFormat, publicKey)
    ) { zoneJoinedEvent =>
      if (state.clientConnections.isEmpty) {
        passivationCountdownActor ! Stop
      }
      context.watch(clientConnection)
      val wasAlreadyPresent = state.clientConnections.values.exists(_ == zoneJoinedEvent.publicKey)
      updateState(zoneJoinedEvent)
      onStateUpdate(state)
      if (!wasAlreadyPresent) {
        deliverNotification(ClientJoinedZoneNotification(zoneId, zoneJoinedEvent.publicKey))
      }
    }

  private[this] def handleQuit(clientConnection: ActorRef)(onStateUpdate: => Unit = ()): Unit =
    persist(
      ZoneQuitEvent(System.currentTimeMillis, clientConnection.path.toSerializationFormat)
    ) { zoneQuitEvent =>
      val publicKey = state.clientConnections(clientConnection.path)
      updateState(zoneQuitEvent)
      onStateUpdate
      val isStillPresent = state.clientConnections.values.exists(_ == publicKey)
      if (!isStillPresent) {
        deliverNotification(ClientQuitZoneNotification(zoneId, publicKey))
      }
      context.unwatch(clientConnection)
      if (state.clientConnections.isEmpty) {
        passivationCountdownActor ! Start
      }
    }

  private[this] def deliverResponse(response: Either[ErrorResponse, ResultResponse],
                                    commandCorrelationId: Option[Either[String, BigDecimal]]): Unit = {
    val sequenceNumber = messageSequenceNumbers(sender().path)
    messageSequenceNumbers = messageSequenceNumbers + (sender().path -> (sequenceNumber + 1))
    deliver(sender().path) { deliveryId =>
      pendingDeliveries = pendingDeliveries + (sender().path -> (pendingDeliveries(sender().path) + deliveryId))
      ResponseWithIds(
        response,
        commandCorrelationId,
        sequenceNumber,
        deliveryId
      )
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
