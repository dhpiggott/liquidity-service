package actors

import java.util.UUID

import actors.ClientConnection.MessageReceivedConfirmation
import actors.ZoneValidator._
import akka.actor._
import akka.contrib.pattern.ShardRegion
import akka.persistence.{AtLeastOnceDelivery, PersistentActor, RecoveryCompleted}
import com.dhpcs.jsonrpc.JsonRpcResponseError
import com.dhpcs.liquidity.models._

import scala.concurrent.duration._

object ZoneValidator {

  def props = Props(new ZoneValidator)

  case class EnvelopedMessage(zoneId: ZoneId, message: Any)

  case class AuthenticatedCommandWithIds(publicKey: PublicKey,
                                         command: Command,
                                         correlationId: Either[String, Int],
                                         sequenceNumber: Long,
                                         deliveryId: Long)

  case class CommandReceivedConfirmation(zoneId: ZoneId, deliveryId: Long)

  case class ZoneAlreadyExists(createZoneCommand: CreateZoneCommand,
                               correlationId: Either[String, Int],
                               sequenceNumber: Long,
                               deliveryId: Long)

  case class ZoneRestarted(zoneId: ZoneId, sequenceNumber: Long, deliveryId: Long)

  case class ResponseWithIds(response: Response,
                             correlationId: Either[String, Int],
                             sequenceNumber: Long,
                             deliveryId: Long)

  case class NotificationWithIds(notification: Notification, sequenceNumber: Long, deliveryId: Long)

  sealed trait Event

  case class ZoneCreatedEvent(zone: Zone) extends Event

  case class ZoneJoinedEvent(clientConnection: ActorRef, publicKey: PublicKey) extends Event

  case class ZoneQuitEvent(clientConnection: ActorRef) extends Event

  case class ZoneNameChangedEvent(name: Option[String]) extends Event

  case class MemberCreatedEvent(member: Member) extends Event

  case class MemberUpdatedEvent(member: Member) extends Event

  case class AccountCreatedEvent(account: Account) extends Event

  case class AccountUpdatedEvent(account: Account) extends Event

  case class TransactionAddedEvent(transaction: Transaction) extends Event

  private val receiveTimeout = 2.minutes

  val idExtractor: ShardRegion.IdExtractor = {

    case EnvelopedMessage(zoneId, message) =>

      (zoneId.id.toString, message)

    case authenticatedCommandWithIds@AuthenticatedCommandWithIds(_, zoneCommand: ZoneCommand, _, _, _) =>

      (zoneCommand.zoneId.id.toString, authenticatedCommandWithIds)

  }

  /**
   * From http://doc.akka.io/docs/akka/2.3.12/contrib/cluster-sharding.html:
   *
   * "Creating a good sharding algorithm is an interesting challenge in itself. Try to produce a uniform distribution,
   * i.e. same amount of entries in each shard. As a rule of thumb, the number of shards should be a factor ten greater
   * than the planned maximum number of cluster nodes."
   */
  private val numberOfShards = 10

  val shardResolver: ShardRegion.ShardResolver = {

    case EnvelopedMessage(zoneId, _) =>

      (math.abs(zoneId.id.hashCode) % numberOfShards).toString

    case AuthenticatedCommandWithIds(_, zoneCommand: ZoneCommand, _, _, _) =>

      (math.abs(zoneCommand.zoneId.id.hashCode) % numberOfShards).toString

  }

  val shardName = "ZoneValidator"

  private case class State(zone: Zone,
                           balances: Map[AccountId, BigDecimal],
                           clientConnections: Map[ActorRef, PublicKey]) {

    def updated(event: Event) = event match {

      case zoneCreatedEvent: ZoneCreatedEvent =>

        copy(
          zone = zoneCreatedEvent.zone
        )

      case ZoneJoinedEvent(clientConnection, publicKey) =>

        copy(
          clientConnections = clientConnections + (clientConnection -> publicKey)
        )

      case ZoneQuitEvent(clientConnection) =>

        copy(
          clientConnections = clientConnections - clientConnection
        )

      case ZoneNameChangedEvent(name) =>

        copy(
          zone = zone.copy(
            name = name
          )
        )

      case MemberCreatedEvent(member) =>

        copy(
          zone = zone.copy(
            members = zone.members + (member.id -> member)
          )
        )

      case MemberUpdatedEvent(member) =>

        copy(
          zone = zone.copy(
            members = zone.members + (member.id -> member)
          )
        )

      case AccountCreatedEvent(account) =>

        copy(
          zone = zone.copy(
            accounts = zone.accounts + (account.id -> account)
          )
        )

      case AccountUpdatedEvent(account) =>

        copy(
          zone = zone.copy(
            accounts = zone.accounts + (account.id -> account)
          )
        )

      case TransactionAddedEvent(transaction) =>

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

  private class PassivationCountdown extends Actor {

    import actors.ZoneValidator.PassivationActor._

    context.setReceiveTimeout(receiveTimeout)

    override def receive: Receive = {

      case ReceiveTimeout =>

        context.parent ! RequestPassivate

      case CommandReceivedEvent =>

      case Start =>

        context.setReceiveTimeout(receiveTimeout)

      case Stop =>

        context.setReceiveTimeout(Duration.Undefined)

    }

  }

  private object PassivationActor {

    case object CommandReceivedEvent

    case object RequestPassivate

    case object Start

    case object Stop

  }

  private def canModify(zone: Zone, memberId: MemberId, publicKey: PublicKey) =
    zone.members.get(memberId).fold[Either[String, Unit]](Left("Member does not exist"))(member =>
      if (publicKey != member.ownerPublicKey) {
        Left("Client's public key does not match Member's public key")
      } else {
        Right(())
      }
    )

  private def canModify(zone: Zone, accountId: AccountId, publicKey: PublicKey) =
    zone.accounts.get(accountId).fold[Either[String, Unit]](Left("Account does not exist"))(account =>
      if (!account.ownerMemberIds.exists(memberId =>
        zone.members.get(memberId).fold(false)(publicKey == _.ownerPublicKey)
      )) {
        Left("Client's public key does not match that of any account owner member")
      } else {
        Right(())
      }
    )

  private def canModify(zone: Zone, accountId: AccountId, actingAs: MemberId, publicKey: PublicKey) =
    zone.accounts.get(accountId).fold[Either[String, Unit]](Left("Account does not exist"))(account =>
      if (!account.ownerMemberIds.contains(actingAs)) {
        Left("Member is not an account owner")
      } else {
        zone.members.get(actingAs).fold[Either[String, Unit]](Left("Member does not exist"))(member =>
          if (publicKey != member.ownerPublicKey) {
            Left("Client's public key does not match Member's public key")
          } else {
            Right(())
          }
        )
      }
    )

  private def checkAccountOwners(zone: Zone, owners: Set[MemberId]) = {
    val invalidAccountOwners = owners -- zone.members.keys
    if (invalidAccountOwners.nonEmpty) {
      Left(s"Invalid account owners: $invalidAccountOwners")
    } else {
      Right(())
    }
  }

  private def checkTransaction(from: AccountId,
                               to: AccountId,
                               value: BigDecimal,
                               zone: Zone,
                               balances: Map[AccountId, BigDecimal]) =
    if (!zone.accounts.contains(from)) {
      Left(s"Invalid transaction source account: $from")
    } else if (!zone.accounts.contains(to)) {
      Left(s"Invalid transaction destination account: $to")
    } else {
      val updatedSourceBalance = balances(from) - value
      if (updatedSourceBalance < 0 && from != zone.equityAccountId) {
        Left(s"Illegal transaction value: $value")
      } else {
        Right(())
      }
    }

}

class ZoneValidator extends PersistentActor with ActorLogging with AtLeastOnceDelivery {

  import ShardRegion.Passivate
  import actors.ZoneValidator.PassivationActor._

  private val passivationActor = context.actorOf(Props[PassivationCountdown])

  private val zoneId = ZoneId(UUID.fromString(self.path.name))

  private var state: State = State(
    null,
    Map.empty.withDefaultValue(BigDecimal(0)),
    Map.empty[ActorRef, PublicKey]
  )

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
      deliver(clientConnection.path, { deliveryId =>
        pendingDeliveries = pendingDeliveries + (sender() -> (pendingDeliveries(clientConnection) + deliveryId))
        NotificationWithIds(notification, sequenceNumber, deliveryId)
      })
    }
  }

  private def deliverResponse(response: Response, commandCorrelationId: Either[String, Int]) {
    val sequenceNumber = messageSequenceNumbers(sender())
    messageSequenceNumbers = messageSequenceNumbers + (sender() -> (sequenceNumber + 1))
    deliver(sender().path, { deliveryId =>
      pendingDeliveries = pendingDeliveries + (sender() -> (pendingDeliveries(sender()) + deliveryId))
      ResponseWithIds(
        response,
        commandCorrelationId,
        sequenceNumber,
        deliveryId
      )
    })
  }

  private def handleJoin(clientConnection: ActorRef, publicKey: PublicKey, onStateUpdate: State => Unit = _ => ()) =
    persist(ZoneJoinedEvent(clientConnection, publicKey)) { zoneJoinedEvent =>

      if (state.clientConnections.isEmpty) {
        passivationActor ! Stop
      }

      context.watch(zoneJoinedEvent.clientConnection)

      val wasAlreadyPresent = state.clientConnections.values.exists(_ == zoneJoinedEvent.publicKey)

      updateState(zoneJoinedEvent)

      log.info(s"${state.clientConnections.size} clients are present")

      onStateUpdate(state)

      if (!wasAlreadyPresent) {
        deliverNotification(ClientJoinedZoneNotification(zoneId, zoneJoinedEvent.publicKey))
      }

    }

  private def handleQuit(clientConnection: ActorRef, onStateUpdate: => Unit = ()) =
    persist(ZoneQuitEvent(clientConnection)) { zoneQuitEvent =>

      val publicKey = state.clientConnections(zoneQuitEvent.clientConnection)

      updateState(zoneQuitEvent)

      log.info(s"${state.clientConnections.size} clients are present")

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

  override def receiveCommand = waitForZone

  override def receiveRecover = {

    case event: Event =>

      updateState(event)

    case RecoveryCompleted =>

      state.clientConnections.keys.foreach(context.watch)

      state.clientConnections.keys.foreach { clientConnection =>
        val sequenceNumber = messageSequenceNumbers(clientConnection)
        messageSequenceNumbers = messageSequenceNumbers + (clientConnection -> (sequenceNumber + 1))
        deliver(clientConnection.path, { deliveryId =>
          pendingDeliveries = pendingDeliveries + (sender() -> (pendingDeliveries(clientConnection) + deliveryId))
          ZoneRestarted(zoneId, sequenceNumber, deliveryId)
        })
      }

      state = state.copy(
        clientConnections = Map.empty
      )

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

  private def waitForZone: Receive = confirmMessageReceipt orElse waitForTimeout orElse {

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

            val equityOwner = Member(MemberId(0), equityOwnerPublicKey, equityOwnerName, equityOwnerMetadata)
            val equityAccount = Account(AccountId(0), Set(equityOwner.id), equityAccountName, equityAccountMetadata)
            val created = System.currentTimeMillis

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
              name,
              metadata
            )

            persist(ZoneCreatedEvent(zone)) { zoneCreatedEvent =>

              updateState(zoneCreatedEvent)

              deliverResponse(
                CreateZoneResponse(
                  zoneCreatedEvent.zone
                ),
                correlationId
              )

            }

          case _ =>

            deliverResponse(
              ErrorResponse(
                JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                "Zone does not exist"
              ),
              correlationId
            )

        }

      }

  }

  private def withZone: Receive = confirmMessageReceipt orElse waitForTimeout orElse {

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
            deliver(sender().path, { deliveryId =>
              pendingDeliveries = pendingDeliveries + (sender() -> (pendingDeliveries(sender()) + deliveryId))
              ZoneAlreadyExists(createZoneCommand, correlationId, sequenceNumber, deliveryId)
            })

          case JoinZoneCommand(_) =>

            if (state.clientConnections.contains(sender())) {

              deliverResponse(
                ErrorResponse(
                  JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                  "Zone already joined"
                ),
                correlationId
              )

            } else {

              handleJoin(sender(), publicKey, { state =>
                deliverResponse(
                  JoinZoneResponse(
                    state.zone,
                    state.clientConnections.values.toSet
                  ),
                  correlationId
                )
              })

            }

          case QuitZoneCommand(_) =>

            if (!state.clientConnections.contains(sender())) {

              deliverResponse(
                ErrorResponse(
                  JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                  "Zone not joined"
                ),
                correlationId
              )

            } else {

              handleQuit(sender(), {
                deliverResponse(
                  QuitZoneResponse,
                  correlationId
                )
              })

            }

          case ChangeZoneNameCommand(_, name) =>

            persist(ZoneNameChangedEvent(name)) { zoneNameChangedEvent =>

              updateState(zoneNameChangedEvent)

              deliverResponse(
                ChangeZoneNameResponse,
                correlationId
              )

              deliverNotification(
                ZoneNameChangedNotification(
                  zoneId,
                  zoneNameChangedEvent.name
                )
              )

            }

          case CreateMemberCommand(_, ownerPublicKey, name, metadata) =>

            val member = Member(
              MemberId(state.zone.members.size),
              ownerPublicKey,
              name,
              metadata
            )

            persist(MemberCreatedEvent(member)) { memberCreatedEvent =>

              updateState(memberCreatedEvent)

              deliverResponse(
                CreateMemberResponse(
                  memberCreatedEvent.member
                ),
                correlationId
              )

              deliverNotification(
                MemberCreatedNotification(
                  zoneId,
                  memberCreatedEvent.member
                )
              )

            }

          case UpdateMemberCommand(_, member) =>

            canModify(state.zone, member.id, publicKey) match {

              case Left(message) =>

                deliverResponse(
                  ErrorResponse(
                    JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                    message
                  ),
                  correlationId
                )

              case Right(_) =>

                persist(MemberUpdatedEvent(member)) { memberUpdatedEvent =>

                  updateState(memberUpdatedEvent)

                  deliverResponse(
                    UpdateMemberResponse,
                    correlationId
                  )

                  deliverNotification(
                    MemberUpdatedNotification(
                      zoneId,
                      memberUpdatedEvent.member
                    )
                  )

                }

            }

          case CreateAccountCommand(_, owners, name, metadata) =>

            checkAccountOwners(state.zone, owners) match {

              case Left(message) =>

                deliverResponse(
                  ErrorResponse(
                    JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                    message
                  ),
                  correlationId
                )

              case Right(_) =>

                val account = Account(
                  AccountId(state.zone.accounts.size),
                  owners,
                  name,
                  metadata
                )

                persist(AccountCreatedEvent(account)) { accountCreatedEvent =>

                  updateState(accountCreatedEvent)

                  deliverResponse(
                    CreateAccountResponse(
                      accountCreatedEvent.account
                    ),
                    correlationId
                  )

                  deliverNotification(
                    AccountCreatedNotification(
                      zoneId,
                      accountCreatedEvent.account
                    )
                  )

                }

            }

          case UpdateAccountCommand(_, account) =>

            canModify(state.zone, account.id, publicKey) match {

              case Left(message) =>

                deliverResponse(
                  ErrorResponse(
                    JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                    message
                  ),
                  correlationId
                )

              case Right(_) =>

                checkAccountOwners(state.zone, account.ownerMemberIds) match {

                  case Left(message) =>

                    deliverResponse(
                      ErrorResponse(
                        JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                        message
                      ),
                      correlationId
                    )

                  case Right(_) =>

                    persist(AccountUpdatedEvent(account)) { accountUpdatedEvent =>

                      updateState(accountUpdatedEvent)

                      deliverResponse(
                        UpdateAccountResponse,
                        correlationId
                      )

                      deliverNotification(
                        AccountUpdatedNotification(
                          zoneId,
                          accountUpdatedEvent.account
                        )
                      )

                    }

                }

            }

          case AddTransactionCommand(_, actingAs, from, to, value, description, metadata) =>

            canModify(state.zone, from, actingAs, publicKey) match {

              case Left(message) =>

                deliverResponse(
                  ErrorResponse(
                    JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                    message
                  ),
                  correlationId
                )

              case Right(_) =>

                checkTransaction(from, to, value, state.zone, state.balances) match {

                  case Left(message) =>

                    deliverResponse(
                      ErrorResponse(
                        JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                        message
                      ),
                      correlationId
                    )

                  case Right(_) =>

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

                    persist(TransactionAddedEvent(transaction)) { transactionAddedEvent =>

                      updateState(transactionAddedEvent)

                      deliverResponse(
                        AddTransactionResponse(
                          transactionAddedEvent.transaction
                        ),
                        correlationId
                      )

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

      }

    case Terminated(clientConnection) =>

      if (state.clientConnections.contains(sender())) {

        handleQuit(clientConnection)

      }

      nextExpectedCommandSequenceNumbers = nextExpectedCommandSequenceNumbers - sender()

      messageSequenceNumbers = messageSequenceNumbers - sender()

      pendingDeliveries(sender()).foreach(confirmDelivery)
      pendingDeliveries = pendingDeliveries - sender()

  }

}
