package actors

import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.{InvalidKeySpecException, X509EncodedKeySpec}
import java.util.UUID

import actors.ClientConnection.MessageReceivedConfirmation
import actors.ZoneValidator._
import akka.actor._
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Publish
import akka.cluster.sharding.ShardRegion
import akka.persistence.{AtLeastOnceDelivery, PersistentActor, RecoveryCompleted}
import com.dhpcs.jsonrpc.{ErrorResponse, JsonRpcResponseError}
import com.dhpcs.liquidity.models._
import controllers.Application
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.duration._

object ZoneValidator {

  def props = Props(new ZoneValidator)

  val Topic = "Zone"

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

  case class ZoneRestarted(zoneId: ZoneId, sequenceNumber: Long, deliveryId: Long)

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

  sealed trait Event {

    def timestamp: Long

  }

  case class ZoneCreatedEvent(timestamp: Long, zone: Zone) extends Event

  case class ZoneJoinedEvent(timestamp: Long, clientConnection: ActorRef, publicKey: PublicKey) extends Event

  case class ZoneQuitEvent(timestamp: Long, clientConnection: ActorRef) extends Event

  case class ZoneNameChangedEvent(timestamp: Long, name: Option[String]) extends Event

  case class MemberCreatedEvent(timestamp: Long, member: Member) extends Event

  case class MemberUpdatedEvent(timestamp: Long, member: Member) extends Event

  case class AccountCreatedEvent(timestamp: Long, account: Account) extends Event

  case class AccountUpdatedEvent(timestamp: Long, account: Account) extends Event

  case class TransactionAddedEvent(timestamp: Long, transaction: Transaction) extends Event

  private val zoneLifetime = 2.days

  val extractEntityId: ShardRegion.ExtractEntityId = {

    case EnvelopedMessage(zoneId, message) =>

      (zoneId.id.toString, message)

    case authenticatedCommandWithIds@AuthenticatedCommandWithIds(_, zoneCommand: ZoneCommand, _, _, _) =>

      (zoneCommand.zoneId.id.toString, authenticatedCommandWithIds)

  }

  /**
    * From http://doc.akka.io/docs/akka/2.4.2/scala/cluster-sharding.html:
    *
    * "Creating a good sharding algorithm is an interesting challenge in itself. Try to produce a uniform distribution,
    * i.e. same amount of entities in each shard. As a rule of thumb, the number of shards should be a factor ten
    * greater than the planned maximum number of cluster nodes."
    */
  private val numberOfShards = 10

  val extractShardId: ShardRegion.ExtractShardId = {

    case EnvelopedMessage(zoneId, _) =>

      (math.abs(zoneId.id.hashCode) % numberOfShards).toString

    case AuthenticatedCommandWithIds(_, zoneCommand: ZoneCommand, _, _, _) =>

      (math.abs(zoneCommand.zoneId.id.hashCode) % numberOfShards).toString

  }

  val shardName = "ZoneValidator"

  private case class State(zone: Zone = null,
                           balances: Map[AccountId, BigDecimal] = Map.empty.withDefaultValue(BigDecimal(0)),
                           clientConnections: Map[ActorRef, PublicKey] = Map.empty[ActorRef, PublicKey]) {

    def updated(event: Event) = event match {

      case zoneCreatedEvent: ZoneCreatedEvent =>

        copy(
          zone = zoneCreatedEvent.zone
        )

      case ZoneJoinedEvent(timestamp, clientConnection, publicKey) =>

        copy(
          clientConnections = clientConnections + (clientConnection -> publicKey)
        )

      case ZoneQuitEvent(timestamp, clientConnection) =>

        copy(
          clientConnections = clientConnections - clientConnection
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

  private class PassivationCountdown extends Actor {

    import actors.ZoneValidator.PassivationCountdown._

    context.setReceiveTimeout(passivationTimeout)

    override def receive: Receive = {

      case ReceiveTimeout =>

        context.parent ! RequestPassivate

      case CommandReceivedEvent =>

      case Start =>

        context.setReceiveTimeout(passivationTimeout)

      case Stop =>

        context.setReceiveTimeout(Duration.Undefined)

    }

  }

  private object PassivationCountdown {

    private val passivationTimeout = 2.minutes

    case object CommandReceivedEvent

    case object RequestPassivate

    case object Start

    case object Stop

  }

  private def checkAccountOwners(zone: Zone, owners: Set[MemberId]) = {
    val invalidAccountOwners = owners -- zone.members.keys
    if (invalidAccountOwners.nonEmpty) {
      Some(s"Invalid account owners: $invalidAccountOwners")
    } else {
      None
    }
  }

  private def checkCanModify(zone: Zone, memberId: MemberId, publicKey: PublicKey) =
    zone.members.get(memberId).fold[Option[String]](Some("Member does not exist"))(member =>
      if (publicKey != member.ownerPublicKey) {
        Some("Client's public key does not match Member's public key")
      } else {
        None
      }
    )

  private def checkCanModify(zone: Zone, accountId: AccountId, publicKey: PublicKey) =
    zone.accounts.get(accountId).fold[Option[String]](Some("Account does not exist"))(account =>
      if (!account.ownerMemberIds.exists(memberId =>
        zone.members.get(memberId).fold(false)(publicKey == _.ownerPublicKey)
      )) {
        Some("Client's public key does not match that of any account owner member")
      } else {
        None
      }
    )

  private def checkCanModify(zone: Zone, accountId: AccountId, actingAs: MemberId, publicKey: PublicKey) =
    zone.accounts.get(accountId).fold[Option[String]](Some("Account does not exist"))(account =>
      if (!account.ownerMemberIds.contains(actingAs)) {
        Some("Member is not an account owner")
      } else {
        zone.members.get(actingAs).fold[Option[String]](Some("Member does not exist"))(member =>
          if (publicKey != member.ownerPublicKey) {
            Some("Client's public key does not match Member's public key")
          } else {
            None
          }
        )
      }
    )

  private def checkOwnerPublicKey(ownerPublicKey: PublicKey) =
    try {
      if (KeyFactory.getInstance("RSA")
        .generatePublic(new X509EncodedKeySpec(ownerPublicKey.value))
        .asInstanceOf[RSAPublicKey].getModulus.bitLength != Application.RequiredKeyLength) {
        Some("Invalid owner public key length")
      } else {
        None
      }
    } catch {
      case _: InvalidKeySpecException =>
        Some("Invalid owner public key type")
    }

  private def checkTagAndMetadata(tag: Option[String], metadata: Option[JsObject]) =
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
                               balances: Map[AccountId, BigDecimal]) =
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

class ZoneValidator extends PersistentActor with ActorLogging with AtLeastOnceDelivery {

  import ShardRegion.Passivate
  import actors.ZoneValidator.PassivationCountdown._
  import context.dispatcher

  private val mediator = DistributedPubSub(context.system).mediator

  private val publishStatusTick = context.system.scheduler.schedule(0.seconds, 30.seconds, self, PublishStatus)

  private val passivationActor = context.actorOf(Props[PassivationCountdown])

  private val zoneId = ZoneId(UUID.fromString(self.path.name))

  private var state = State()

  private var nextExpectedCommandSequenceNumbers = Map.empty[ActorRef, Long].withDefaultValue(0L)
  private var messageSequenceNumbers = Map.empty[ActorRef, Long].withDefaultValue(0L)
  private var pendingDeliveries = Map.empty[ActorRef, Set[Long]].withDefaultValue(Set.empty)

  private def confirmMessageReceipt: Receive = {

    case MessageReceivedConfirmation(deliveryId) =>

      confirmDelivery(deliveryId)

      pendingDeliveries = pendingDeliveries + (sender() -> (pendingDeliveries(sender()) - deliveryId))

      if (pendingDeliveries(sender()).isEmpty) {

        pendingDeliveries = pendingDeliveries - sender()

      }

  }

  private def deliverNotification(notification: Notification) {
    state.clientConnections.keys.foreach { clientConnection =>
      val sequenceNumber = messageSequenceNumbers(clientConnection)
      messageSequenceNumbers = messageSequenceNumbers + (clientConnection -> (sequenceNumber + 1))
      deliver(clientConnection.path) { deliveryId =>
        pendingDeliveries = pendingDeliveries + (sender() -> (pendingDeliveries(clientConnection) + deliveryId))
        NotificationWithIds(notification, sequenceNumber, deliveryId)
      }
    }
  }

  private def deliverResponse(response: Either[ErrorResponse, ResultResponse],
                              commandCorrelationId: Option[Either[String, BigDecimal]]) {
    val sequenceNumber = messageSequenceNumbers(sender())
    messageSequenceNumbers = messageSequenceNumbers + (sender() -> (sequenceNumber + 1))
    deliver(sender().path) { deliveryId =>
      pendingDeliveries = pendingDeliveries + (sender() -> (pendingDeliveries(sender()) + deliveryId))
      ResponseWithIds(
        response,
        commandCorrelationId,
        sequenceNumber,
        deliveryId
      )
    }
  }

  private def handleJoin(clientConnection: ActorRef, publicKey: PublicKey, onStateUpdate: State => Unit = _ => ()) =
    persist(ZoneJoinedEvent(System.currentTimeMillis, clientConnection, publicKey)) { zoneJoinedEvent =>

      if (state.clientConnections.isEmpty) {
        passivationActor ! Stop
      }

      context.watch(zoneJoinedEvent.clientConnection)

      val wasAlreadyPresent = state.clientConnections.values.exists(_ == zoneJoinedEvent.publicKey)

      updateState(zoneJoinedEvent)

      onStateUpdate(state)

      if (!wasAlreadyPresent) {
        deliverNotification(ClientJoinedZoneNotification(zoneId, zoneJoinedEvent.publicKey))
      }

    }

  private def handleQuit(clientConnection: ActorRef, onStateUpdate: => Unit = ()) =
    persist(ZoneQuitEvent(System.currentTimeMillis, clientConnection)) { zoneQuitEvent =>

      val publicKey = state.clientConnections(zoneQuitEvent.clientConnection)

      updateState(zoneQuitEvent)

      onStateUpdate

      val isStillPresent = state.clientConnections.values.exists(_ == publicKey)

      if (!isStillPresent) {
        deliverNotification(ClientQuitZoneNotification(zoneId, publicKey))
      }

      context.unwatch(zoneQuitEvent.clientConnection)

      if (state.clientConnections.isEmpty) {
        passivationActor ! Start
      }

    }

  override def persistenceId = zoneId.toString

  def publishStatus: Receive = {

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

  override def postStop() {
    log.info(s"Stopped actor for ${zoneId.id}")
    publishStatusTick.cancel()
    super.postStop()
  }

  override def preStart() {
    log.info(s"Started actor for ${zoneId.id}")
    super.preStart()
  }

  override def receiveCommand = waitForZone

  override def receiveRecover = {

    case event: Event =>

      updateState(event)

    case RecoveryCompleted =>

      state.clientConnections.keys.foreach(context.watch)

      state.clientConnections.keys.foreach { clientConnection =>
        val sequenceNumber = messageSequenceNumbers(clientConnection)
        messageSequenceNumbers = messageSequenceNumbers + (clientConnection -> (sequenceNumber + 1))
        deliver(clientConnection.path) { deliveryId =>
          pendingDeliveries = pendingDeliveries + (sender() -> (pendingDeliveries(clientConnection) + deliveryId))
          ZoneRestarted(zoneId, sequenceNumber, deliveryId)
        }
      }

      state = state.copy(
        clientConnections = Map.empty
      )

      self ! PublishStatus

  }

  private def updateState(event: Event) {
    state = state.updated(event)
    if (event.isInstanceOf[ZoneCreatedEvent]) {
      context.become(withZone)
    }
  }

  private def waitForTimeout: Receive = {

    case RequestPassivate =>

      context.parent ! Passivate(stopMessage = PoisonPill)

  }

  private def waitForZone: Receive = publishStatus orElse confirmMessageReceipt orElse waitForTimeout orElse {

    case AuthenticatedCommandWithIds(publicKey, command, correlationId, sequenceNumber, deliveryId) =>

      passivationActor ! CommandReceivedEvent

      val nextExpectedCommandSequenceNumber = nextExpectedCommandSequenceNumbers(sender())

      if (sequenceNumber <= nextExpectedCommandSequenceNumber) {

        sender ! CommandReceivedConfirmation(zoneId, deliveryId)

      }

      if (sequenceNumber == nextExpectedCommandSequenceNumber) {

        nextExpectedCommandSequenceNumbers = nextExpectedCommandSequenceNumbers + (sender() -> (sequenceNumber + 1))

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
                val expires = created + zoneLifetime.toMillis

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

                persist(ZoneCreatedEvent(System.currentTimeMillis, zone)) { zoneCreatedEvent =>

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

  private def withZone: Receive = publishStatus orElse confirmMessageReceipt orElse waitForTimeout orElse {

    case AuthenticatedCommandWithIds(publicKey, command, correlationId, sequenceNumber, deliveryId) =>

      passivationActor ! CommandReceivedEvent

      val nextExpectedCommandSequenceNumber = nextExpectedCommandSequenceNumbers(sender())

      if (sequenceNumber <= nextExpectedCommandSequenceNumber) {

        sender ! CommandReceivedConfirmation(zoneId, deliveryId)

      }

      if (sequenceNumber == nextExpectedCommandSequenceNumber) {

        nextExpectedCommandSequenceNumbers = nextExpectedCommandSequenceNumbers + (sender() -> (sequenceNumber + 1))

        command match {

          case createZoneCommand: CreateZoneCommand =>

            val sequenceNumber = messageSequenceNumbers(sender())
            messageSequenceNumbers = messageSequenceNumbers + (sender() -> (sequenceNumber + 1))
            deliver(sender().path) { deliveryId =>
              pendingDeliveries = pendingDeliveries + (sender() -> (pendingDeliveries(sender()) + deliveryId))
              ZoneAlreadyExists(createZoneCommand, correlationId, sequenceNumber, deliveryId)
            }

          case JoinZoneCommand(_) =>

            if (state.clientConnections.contains(sender())) {

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

              handleJoin(sender(), publicKey, { state =>

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

              })

            }

          case QuitZoneCommand(_) =>

            if (!state.clientConnections.contains(sender())) {

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

              handleQuit(sender(), {

                deliverResponse(
                  Right(
                    QuitZoneResponse
                  ),
                  correlationId
                )

                self ! PublishStatus

              })

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

                val transaction = Transaction(
                  TransactionId(state.zone.transactions.size),
                  from,
                  to,
                  value,
                  actingAs,
                  System.currentTimeMillis,
                  description,
                  metadata
                )

                persist(TransactionAddedEvent(System.currentTimeMillis, transaction)) { transactionAddedEvent =>

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

    case Terminated(clientConnection) =>

      handleQuit(clientConnection, {

        self ! PublishStatus

      })

      nextExpectedCommandSequenceNumbers = nextExpectedCommandSequenceNumbers - clientConnection

      messageSequenceNumbers = messageSequenceNumbers - clientConnection

      pendingDeliveries(clientConnection).foreach(confirmDelivery)
      pendingDeliveries = pendingDeliveries - clientConnection

  }

}
