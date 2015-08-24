package actors

import java.util.UUID

import actors.ZoneValidator._
import akka.actor._
import akka.contrib.pattern.ShardRegion
import akka.persistence.PersistentActor
import com.dhpcs.jsonrpc.JsonRpcResponseError
import com.dhpcs.liquidity.models._
import play.api.libs.json.JsObject

import scala.concurrent.duration._

object ZoneValidator {

  def props = Props(new ZoneValidator)

  case class EnvelopedMessage(zoneId: ZoneId, message: Any)

  case class AuthenticatedCommandWithId(publicKey: PublicKey, command: Command, id: Either[String, Int])

  case class ZoneAlreadyExists(createZoneCommand: CreateZoneCommand)

  case class ResponseWithId(response: Response, id: Either[String, Int])

  sealed trait Event

  case class ZoneCreatedEvent(name: Option[String],
                              equityOwner: Member,
                              equityAccount: Account,
                              created: Long,
                              metadata: Option[JsObject]) extends Event

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

    case authenticatedCommandWithId@AuthenticatedCommandWithId(_, zoneCommand: ZoneCommand, _) =>

      (zoneCommand.zoneId.id.toString, authenticatedCommandWithId)

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

    case AuthenticatedCommandWithId(_, zoneCommand: ZoneCommand, _) =>

      (math.abs(zoneCommand.zoneId.id.hashCode) % numberOfShards).toString

  }

  val shardName = "ZoneValidator"

  private case class State(balances: Map[AccountId, BigDecimal],
                           clientConnections: Map[ActorRef, PublicKey],
                           zone: Zone) {

    def updated(event: Event) = event match {

      case ZoneCreatedEvent(name, equityOwner, equityAccount, created, metadata) =>

        copy(
          zone = Zone(
            name,
            equityAccount.id,
            Map(
              equityOwner.id -> equityOwner
            ),
            Map(
              equityAccount.id -> equityAccount
            ),
            Map.empty,
            created,
            metadata
          )
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

class ZoneValidator extends PersistentActor with ActorLogging {

  import ShardRegion.Passivate

  context.setReceiveTimeout(receiveTimeout)

  private val zoneId = ZoneId(UUID.fromString(self.path.name))

  private var state: State = State(
    Map.empty.withDefaultValue(BigDecimal(0)),
    Map.empty[ActorRef, PublicKey],
    null
  )

  private def handleJoin(clientConnection: ActorRef, publicKey: PublicKey) {
    context.watch(clientConnection)
    val wasAlreadyPresent = state.clientConnections.values.exists(_ == publicKey)
    val newClientConnections = state.clientConnections + (clientConnection -> publicKey)
    if (!wasAlreadyPresent) {
      val clientJoinedZoneNotification = ClientJoinedZoneNotification(zoneId, publicKey)
      newClientConnections.keys.foreach(_ ! clientJoinedZoneNotification)
    }
    log.debug(s"${newClientConnections.size} clients are present")
    if (state.clientConnections.isEmpty) {
      context.setReceiveTimeout(Duration.Undefined)
    }
    state = state.copy(
      clientConnections = newClientConnections
    )
  }

  private def handleQuit(clientConnection: ActorRef) {
    context.unwatch(clientConnection)
    val publicKey = state.clientConnections(clientConnection)
    val newClientConnections = state.clientConnections - clientConnection
    val isStillPresent = newClientConnections.values.exists(_ == publicKey)
    if (!isStillPresent) {
      val clientQuitZoneNotification = ClientQuitZoneNotification(zoneId, publicKey)
      newClientConnections.keys.foreach(_ ! clientQuitZoneNotification)
    }
    log.debug(s"${newClientConnections.size} clients are present")
    state = state.copy(
      clientConnections = newClientConnections
    )
    if (state.clientConnections.isEmpty) {
      context.setReceiveTimeout(receiveTimeout)
    }
  }

  override def persistenceId = zoneId.toString

  override def preRestart(reason: Throwable, message: Option[Any]) {
    val zoneTerminatedNotification = ZoneTerminatedNotification(zoneId)
    state.clientConnections.keys.foreach(_ ! zoneTerminatedNotification)
    super.preRestart(reason, message)
  }

  override def receiveCommand = waitForZone

  override def receiveRecover = {

    case event: Event => updateState(event)

  }

  private def updateState(event: Event) {
    state = state.updated(event)
    if (event.isInstanceOf[ZoneCreatedEvent]) {
      context.become(withZone)
    }
  }

  private def waitForTimeout: Receive = {

    case ReceiveTimeout =>

      log.debug("Received ReceiveTimeout")

      context.parent ! Passivate(stopMessage = PoisonPill)

  }

  private def waitForZone: Receive = waitForTimeout orElse {

    case AuthenticatedCommandWithId(publicKey, command, id) =>

      command match {

        case CreateZoneCommand(name,
        equityOwnerName,
        equityOwnerPublicKey,
        equityOwnerMetadata,
        equityAccountName,
        equityAccountMetadata,
        metadata) =>

          val equityOwnerId = MemberId(0)
          val equityOwner = Member(equityOwnerId, equityOwnerName, equityOwnerPublicKey, equityOwnerMetadata)
          val equityAccountId = AccountId(0)
          val equityAccount = Account(equityAccountId, equityAccountName, Set(equityOwnerId), equityAccountMetadata)
          val created = System.currentTimeMillis

          persist(ZoneCreatedEvent(name, equityOwner, equityAccount, created, metadata)) { zoneCreatedEvent =>

            updateState(zoneCreatedEvent)

            sender !
              ResponseWithId(
                CreateZoneResponse(
                  zoneId,
                  equityOwner,
                  equityAccount,
                  created
                ),
                id
              )

          }

        case _ =>

          sender !
            ResponseWithId(
              ErrorResponse(
                JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                "Zone does not exist"
              ),
              id
            )

      }

  }

  private def withZone: Receive = waitForTimeout orElse {

    case AuthenticatedCommandWithId(publicKey, command, id) =>

      command match {

        case createZoneCommand: CreateZoneCommand =>

          sender ! ZoneAlreadyExists(createZoneCommand)

        case _: JoinZoneCommand =>

          sender !
            ResponseWithId(
              JoinZoneResponse(
                state.zone,
                state.clientConnections.values.toSet + publicKey
              ),
              id
            )

          if (!state.clientConnections.contains(sender())) {

            handleJoin(sender(), publicKey)

          }

        case _: QuitZoneCommand =>

          sender !
            ResponseWithId(
              QuitZoneResponse,
              id
            )

          if (state.clientConnections.contains(sender())) {

            handleQuit(sender())

          }

        case ChangeZoneNameCommand(_, name) =>

          persist(ZoneNameChangedEvent(name)) { zoneNameChangedEvent =>

            updateState(zoneNameChangedEvent)

            sender !
              ResponseWithId(
                ChangeZoneNameResponse,
                id
              )

            val zoneNameSetNotification = ZoneNameChangedNotification(
              zoneId,
              name
            )
            state.clientConnections.keys.foreach(_ ! zoneNameSetNotification)

          }

        case CreateMemberCommand(_, name, ownerPublicKey, metadata) =>

          val memberId = MemberId(state.zone.members.size)
          val member = Member(
            memberId,
            name,
            ownerPublicKey,
            metadata
          )

          persist(MemberCreatedEvent(member)) { memberCreatedEvent =>

            updateState(memberCreatedEvent)

            sender !
              ResponseWithId(
                CreateMemberResponse(
                  member
                ),
                id
              )

            val memberCreatedNotification = MemberCreatedNotification(
              zoneId,
              member
            )
            state.clientConnections.keys.foreach(_ ! memberCreatedNotification)

          }

        case UpdateMemberCommand(_, member) =>

          canModify(state.zone, member.id, publicKey) match {

            case Left(message) =>

              sender !
                ResponseWithId(
                  ErrorResponse(
                    JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                    message
                  ),
                  id
                )

            case Right(_) =>

              persist(MemberUpdatedEvent(member)) { memberUpdatedEvent =>

                updateState(memberUpdatedEvent)

                sender !
                  ResponseWithId(
                    UpdateMemberResponse,
                    id
                  )

                val memberUpdatedNotification = MemberUpdatedNotification(
                  zoneId,
                  member
                )
                state.clientConnections.keys.foreach(_ ! memberUpdatedNotification)

              }

          }

        case CreateAccountCommand(_, name, owners, metadata) =>

          checkAccountOwners(state.zone, owners) match {

            case Left(message) =>

              sender !
                ResponseWithId(
                  ErrorResponse(
                    JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                    message
                  ),
                  id
                )

            case Right(_) =>

              val accountId = AccountId(state.zone.accounts.size)
              val account = Account(
                accountId,
                name,
                owners,
                metadata
              )

              persist(AccountCreatedEvent(account)) { accountCreatedEvent =>

                updateState(accountCreatedEvent)

                sender !
                  ResponseWithId(
                    CreateAccountResponse(
                      account
                    ),
                    id
                  )

                val accountCreatedNotification = AccountCreatedNotification(
                  zoneId,
                  account
                )
                state.clientConnections.keys.foreach(_ ! accountCreatedNotification)

              }

          }

        case UpdateAccountCommand(_, account) =>

          canModify(state.zone, account.id, publicKey) match {

            case Left(message) =>

              sender !
                ResponseWithId(
                  ErrorResponse(
                    JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                    message
                  ),
                  id
                )

            case Right(_) =>

              checkAccountOwners(state.zone, account.ownerMemberIds) match {

                case Left(message) =>

                  sender !
                    ResponseWithId(
                      ErrorResponse(
                        JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                        message
                      ),
                      id
                    )

                case Right(_) =>

                  persist(AccountUpdatedEvent(account)) { accountUpdatedEvent =>

                    updateState(accountUpdatedEvent)

                    sender !
                      ResponseWithId(
                        UpdateAccountResponse,
                        id
                      )

                    val accountUpdatedNotification = AccountUpdatedNotification(
                      zoneId,
                      account
                    )
                    state.clientConnections.keys.foreach(_ ! accountUpdatedNotification)

                  }

              }

          }

        case AddTransactionCommand(_, actingAs, description, from, to, value, metadata) =>

          canModify(state.zone, from, actingAs, publicKey) match {

            case Left(message) =>

              sender !
                ResponseWithId(
                  ErrorResponse(
                    JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                    message
                  ),
                  id
                )

            case Right(_) =>

              checkTransaction(from, to, value, state.zone, state.balances) match {

                case Left(message) =>

                  sender !
                    ResponseWithId(
                      ErrorResponse(
                        JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                        message
                      ),
                      id
                    )

                case Right(_) =>

                  val transactionId = TransactionId(state.zone.transactions.size)
                  val created = System.currentTimeMillis
                  val transaction = Transaction(
                    transactionId,
                    description,
                    from,
                    to,
                    value,
                    actingAs,
                    created,
                    metadata
                  )

                  persist(TransactionAddedEvent(transaction)) { transactionAddedEvent =>

                    updateState(transactionAddedEvent)

                    sender !
                      ResponseWithId(
                        AddTransactionResponse(
                          transaction
                        ),
                        id
                      )

                    val transactionAddedNotification = TransactionAddedNotification(
                      zoneId,
                      transaction
                    )
                    state.clientConnections.keys.foreach(_ ! transactionAddedNotification)

                  }

              }

          }

      }

    case Terminated(clientConnection) =>

      handleQuit(clientConnection)

  }

}
