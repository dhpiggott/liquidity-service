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
                              equityOwnerId: MemberId,
                              equityOwner: Member,
                              equityAccountId: AccountId,
                              equityAccount: Account,
                              created: Long,
                              metadata: Option[JsObject]) extends Event

  case class ZoneNameChangedEvent(name: Option[String]) extends Event

  case class MemberCreatedEvent(memberId: MemberId, member: Member) extends Event

  case class MemberUpdatedEvent(memberId: MemberId, member: Member) extends Event

  case class AccountCreatedEvent(accountId: AccountId, account: Account) extends Event

  case class AccountUpdatedEvent(accountId: AccountId, account: Account) extends Event

  case class TransactionAddedEvent(transactionId: TransactionId, transaction: Transaction) extends Event

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

      case ZoneCreatedEvent(name, equityOwnerId, equityOwner, equityAccountId, equityAccount, created, metadata) =>

        copy(
          zone = Zone(
            name,
            equityAccountId,
            Map(
              equityOwnerId -> equityOwner
            ),
            Map(
              equityAccountId -> equityAccount.copy(
                owners = Set(equityOwnerId)
              )
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

      case MemberCreatedEvent(memberId, member) =>

        copy(
          zone = zone.copy(
            members = zone.members + (memberId -> member)
          )
        )

      case MemberUpdatedEvent(memberId, member) =>

        copy(
          zone = zone.copy(
            members = zone.members + (memberId -> member)
          )
        )

      case AccountCreatedEvent(accountId, account) =>

        copy(
          zone = zone.copy(
            accounts = zone.accounts + (accountId -> account)
          )
        )

      case AccountUpdatedEvent(accountId, account) =>

        copy(
          zone = zone.copy(
            accounts = zone.accounts + (accountId -> account)
          )
        )

      case TransactionAddedEvent(transactionId, transaction) =>

        val updatedSourceBalance = balances(transaction.from) - transaction.value
        val updatedDestinationBalance = balances(transaction.to) + transaction.value
        copy(
          balances = balances +
            (transaction.from -> updatedSourceBalance) +
            (transaction.to -> updatedDestinationBalance),
          zone = zone.copy(
            transactions = zone.transactions + (transactionId -> transaction)
          )
        )

    }

  }

  private def canModify(zone: Zone, memberId: MemberId, publicKey: PublicKey) =
    zone.members.get(memberId).fold[Either[String, Unit]](Left("Member does not exist"))(member =>
      if (publicKey != member.publicKey) {
        Left("Client's public key does not match Member's public key")
      } else {
        Right(())
      }
    )

  private def canModify(zone: Zone, accountId: AccountId, publicKey: PublicKey) =
    zone.accounts.get(accountId).fold[Either[String, Unit]](Left("Account does not exist"))(account =>
      if (!account.owners.exists(memberId =>
        zone.members.get(memberId).fold(false)(publicKey == _.publicKey)
      )) {
        Left("Client's public key does not match that of any account owner member")
      } else {
        Right(())
      }
    )

  private def canModify(zone: Zone, accountId: AccountId, actingAs: MemberId, publicKey: PublicKey) =
    zone.accounts.get(accountId).fold[Either[String, Unit]](Left("Account does not exist"))(account =>
      if (!account.owners.contains(actingAs)) {
        Left("Member is not an account owner")
      } else {
        zone.members.get(actingAs).fold[Either[String, Unit]](Left("Member does not exist"))(member =>
          if (publicKey != member.publicKey) {
            Left("Client's public key does not match Member's public key")
          } else {
            Right(())
          }
        )
      }
    )

  private def checkAccountOwners(zone: Zone, account: Account) = {
    val invalidAccountOwners = account.owners -- zone.members.keys
    if (invalidAccountOwners.nonEmpty) {
      Left(s"Invalid account owners: $invalidAccountOwners")
    } else {
      Right(())
    }
  }

  private def checkTransaction(transaction: Transaction,
                               zone: Zone,
                               balances: Map[AccountId, BigDecimal]) =
    if (!zone.accounts.contains(transaction.from)) {
      Left(s"Invalid transaction source account: ${transaction.from}")
    } else if (!zone.accounts.contains(transaction.to)) {
      Left(s"Invalid transaction destination account: ${transaction.to}")
    } else {
      val updatedSourceBalance = balances(transaction.from) - transaction.value
      if (updatedSourceBalance < 0 && transaction.from != zone.equityAccountId) {
        Left(s"Illegal transaction value: ${transaction.value}")
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

        case CreateZoneCommand(name, equityOwner, equityAccount, metadata) =>

          val equityOwnerId = MemberId.generate
          val equityAccountId = AccountId.generate
          val created = System.currentTimeMillis

          persist(
            ZoneCreatedEvent(name, equityOwnerId, equityOwner, equityAccountId, equityAccount, created, metadata)
          ) { zoneCreatedEvent =>

            updateState(zoneCreatedEvent)

            sender !
              ResponseWithId(
                CreateZoneResponse(
                  zoneId,
                  equityOwnerId,
                  equityAccountId,
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

          log.warning(s"Received command from ${publicKey.fingerprint} to operate on non-existing zone")

      }

  }

  private def withZone: Receive = waitForTimeout orElse {

    case AuthenticatedCommandWithId(publicKey, command, id) =>

      command match {

        case createZoneCommand: CreateZoneCommand =>

          sender ! ZoneAlreadyExists(createZoneCommand)

          log.info(s"Received command from ${publicKey.fingerprint} to create already existing zone")

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

        case CreateMemberCommand(_, member) =>

          def freshMemberId: MemberId = {
            val memberId = MemberId.generate
            if (!state.zone.members.contains(memberId)) {
              memberId
            } else {
              freshMemberId
            }
          }
          val memberId = freshMemberId

          persist(MemberCreatedEvent(memberId, member)) { memberCreatedEvent =>

            updateState(memberCreatedEvent)

            sender !
              ResponseWithId(
                CreateMemberResponse(
                  memberId
                ),
                id
              )

            val memberCreatedNotification = MemberCreatedNotification(
              zoneId,
              memberId,
              member
            )
            state.clientConnections.keys.foreach(_ ! memberCreatedNotification)

          }

        case UpdateMemberCommand(_, memberId, member) =>

          canModify(state.zone, memberId, publicKey) match {

            case Left(error) =>

              sender !
                ResponseWithId(
                  ErrorResponse(
                    JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                    error,
                    None
                  ),
                  id
                )

              log.warning(s"Received invalid command from ${publicKey.fingerprint} to update $memberId")

            case Right(_) =>

              persist(MemberUpdatedEvent(memberId, member)) { memberUpdatedEvent =>

                updateState(memberUpdatedEvent)

                sender !
                  ResponseWithId(
                    UpdateMemberResponse,
                    id
                  )

                val memberUpdatedNotification = MemberUpdatedNotification(
                  zoneId,
                  memberId,
                  member
                )
                state.clientConnections.keys.foreach(_ ! memberUpdatedNotification)

              }

          }

        case CreateAccountCommand(_, account) =>

          checkAccountOwners(state.zone, account) match {

            case Left(error) =>

              sender !
                ResponseWithId(
                  ErrorResponse(
                    JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                    error,
                    None
                  ),
                  id
                )

              log.warning(s"Received invalid command from ${publicKey.fingerprint} to create $account")

            case Right(_) =>

              def freshAccountId: AccountId = {
                val accountId = AccountId.generate
                if (!state.zone.accounts.contains(accountId)) {
                  accountId
                } else {
                  freshAccountId
                }
              }
              val accountId = freshAccountId

              persist(AccountCreatedEvent(accountId, account)) { accountCreatedEvent =>

                updateState(accountCreatedEvent)

                sender !
                  ResponseWithId(
                    CreateAccountResponse(
                      accountId
                    ),
                    id
                  )

                val accountCreatedNotification = AccountCreatedNotification(
                  zoneId,
                  accountId,
                  account
                )
                state.clientConnections.keys.foreach(_ ! accountCreatedNotification)

              }

          }

        case UpdateAccountCommand(_, accountId, account) =>

          canModify(state.zone, accountId, publicKey) match {

            case Left(error) =>

              sender !
                ResponseWithId(
                  ErrorResponse(
                    JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                    error,
                    None
                  ),
                  id
                )

              log.warning(s"Received invalid command from ${publicKey.fingerprint} to update $accountId")

            case Right(_) =>

              checkAccountOwners(state.zone, account) match {

                case Left(error) =>

                  sender !
                    ResponseWithId(
                      ErrorResponse(
                        JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                        error,
                        None
                      ),
                      id
                    )

                  log.warning(s"Received invalid command from ${publicKey.fingerprint} to update $accountId")

                case Right(_) =>

                  persist(AccountUpdatedEvent(accountId, account)) { accountUpdatedEvent =>

                    updateState(accountUpdatedEvent)

                    sender !
                      ResponseWithId(
                        UpdateAccountResponse,
                        id
                      )

                    val accountUpdatedNotification = AccountUpdatedNotification(
                      zoneId,
                      accountId,
                      account
                    )
                    state.clientConnections.keys.foreach(_ ! accountUpdatedNotification)

                  }

              }

          }

        case AddTransactionCommand(_, actingAs, description, from, to, value, metadata) =>

          canModify(state.zone, from, actingAs, publicKey) match {

            case Left(error) =>

              sender !
                ResponseWithId(
                  ErrorResponse(
                    JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                    error,
                    None
                  ),
                  id
                )

              log.warning(s"Received invalid command from ${publicKey.fingerprint} to add transaction on $from")

            case Right(_) =>

              val created = System.currentTimeMillis
              val transaction = Transaction(
                description,
                from,
                to,
                value,
                actingAs,
                created,
                metadata
              )

              checkTransaction(transaction, state.zone, state.balances) match {

                case Left(message) =>

                  sender !
                    ResponseWithId(
                      ErrorResponse(
                        JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                        message,
                        None
                      ),
                      id
                    )

                  log.warning(s"Received invalid command from ${publicKey.fingerprint} to add $transaction")

                case Right(_) =>

                  def freshTransactionId: TransactionId = {
                    val transactionId = TransactionId.generate
                    if (!state.zone.transactions.contains(transactionId)) {
                      transactionId
                    } else {
                      freshTransactionId
                    }
                  }
                  val transactionId = freshTransactionId

                  persist(TransactionAddedEvent(transactionId, transaction)) { transactionAddedEvent =>

                    updateState(transactionAddedEvent)

                    sender !
                      ResponseWithId(
                        AddTransactionResponse(
                          transactionId,
                          created
                        ),
                        id
                      )

                    val transactionAddedNotification = TransactionAddedNotification(
                      zoneId,
                      transactionId,
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
