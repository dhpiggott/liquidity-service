package actors

import actors.ZoneValidator.{AuthenticatedCommandWithId, ResponseWithId}
import akka.actor._
import com.dhpcs.jsonrpc.JsonRpcResponseError
import com.dhpcs.liquidity.models._

object ZoneValidator {

  def props(zoneId: ZoneId) = Props(new ZoneValidator(zoneId))

  case class AuthenticatedCommandWithId(publicKey: PublicKey, command: Command, id: Either[String, Int])

  case class ResponseWithId(response: Response, id: Either[String, Int])

}

class ZoneValidator(zoneId: ZoneId) extends Actor with ActorLogging {

  // TODO?
  var presentClients = Map.empty[ActorRef, PublicKey]
  var accountBalances = Map.empty[AccountId, BigDecimal].withDefaultValue(BigDecimal(0))

  def canModify(zone: Zone, memberId: MemberId, publicKey: PublicKey) =
    zone.members.get(memberId).fold(false)(_.publicKey == publicKey)

  def canModify(zone: Zone, accountId: AccountId, publicKey: PublicKey) =
    zone.accounts.get(accountId).fold(false)(
      _.owners.exists { memberId =>
        zone.members.get(memberId).fold(false)(_.publicKey == publicKey)
      }
    )

  def handleJoin(clientConnection: ActorRef, publicKey: PublicKey) {
    context.watch(sender())
    val wasAlreadyPresent = presentClients.values.exists(_ == publicKey)
    presentClients += (clientConnection -> publicKey)
    if (!wasAlreadyPresent) {
      val clientJoinedZoneNotification = ClientJoinedZoneNotification(zoneId, publicKey)
      presentClients.keys.foreach(_ ! clientJoinedZoneNotification)
    }
    log.debug(s"${presentClients.size} clients are present")
  }

  def handleQuit(clientConnection: ActorRef) {
    context.unwatch(sender())
    val publicKey = presentClients(clientConnection)
    presentClients -= clientConnection
    val isStillPresent = presentClients.values.exists(_ == publicKey)
    if (!isStillPresent) {
      val clientQuitZoneNotification = ClientQuitZoneNotification(zoneId, publicKey)
      presentClients.keys.foreach(_ ! clientQuitZoneNotification)
    }
    if (presentClients.nonEmpty) {
      log.debug(s"${presentClients.size} clients are present")
      // TODO: Disabled to simplify client testing until persistence is implemented
      //    } else {
      //      log.debug(s"No clients are present; requesting termination")
      //      context.parent ! TerminationRequest
    }
  }

  def receive = waitingForCanonicalZone

  def waitingForCanonicalZone: Receive = {

    case AuthenticatedCommandWithId(publicKey, command, id) =>

      command match {

        case CreateZoneCommand(name, zoneType, equityHolderMember, equityHolderAccount) =>

          val timestamp = System.currentTimeMillis

          val equityHolderMemberId = MemberId.generate
          val equityHolderAccountId = AccountId.generate

          val zone = Zone(
            name,
            zoneType,
            equityHolderMemberId,
            equityHolderMember,
            equityHolderAccountId,
            equityHolderAccount,
            timestamp
          )

          sender !
            ResponseWithId(
              CreateZoneResponse(
                zoneId,
                equityHolderMemberId,
                equityHolderAccountId
              ),
              id
            )

          context.become(receiveWithCanonicalZone(zone))

        case _ =>

          sender !
            ResponseWithId(
              ErrorResponse(
                JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                "Zone does not exist",
                None
              ),
              id
            )

          log.warning(s"Received command from ${publicKey.fingerprint} to operate on non-existing zone")

      }

    case Terminated(clientConnection) =>

      handleQuit(clientConnection)

  }

  def receiveWithCanonicalZone(canonicalZone: Zone): Receive = {

    case AuthenticatedCommandWithId(publicKey, command, id) =>

      command match {

        case _: CreateZoneCommand =>

          sender !
            ResponseWithId(
              ErrorResponse(
                JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                "Zone already exists",
                None
              ),
              id
            )

          log.warning(s"Received command from ${publicKey.fingerprint} to create already existing zone")

        case _: JoinZoneCommand =>

          sender !
            ResponseWithId(
              JoinZoneResponse(
                canonicalZone,
                presentClients.values.toSet
              ),
              id
            )

          if (!presentClients.contains(sender())) {

            handleJoin(sender(), publicKey)

          }

        case _: QuitZoneCommand =>

          sender !
            ResponseWithId(
              QuitZoneResponse,
              id
            )

          if (presentClients.contains(sender())) {

            handleQuit(sender())

          }

        case SetZoneNameCommand(_, name) =>

          val timestamp = System.currentTimeMillis

          sender !
            ResponseWithId(
              SetZoneNameResponse,
              id
            )

          val newCanonicalZone = canonicalZone.copy(
            name = name,
            lastModified = timestamp
          )
          val zoneNameSetNotification = ZoneNameSetNotification(zoneId, timestamp, name)
          presentClients.keys.foreach(_ ! zoneNameSetNotification)

          context.become(receiveWithCanonicalZone(newCanonicalZone))

        case CreateMemberCommand(_, member) =>

          val timestamp = System.currentTimeMillis

          def freshMemberId: MemberId = {
            val memberId = MemberId.generate
            if (!canonicalZone.members.contains(memberId)) {
              memberId
            } else {
              freshMemberId
            }
          }
          val memberId = freshMemberId

          sender !
            ResponseWithId(
              CreateMemberResponse(
                memberId
              ),
              id
            )

          val newCanonicalZone = canonicalZone.copy(
            members = canonicalZone.members + (memberId -> member),
            lastModified = timestamp
          )
          val memberCreatedNotification = MemberCreatedNotification(zoneId, timestamp, memberId, member)
          presentClients.keys.foreach(_ ! memberCreatedNotification)

          context.become(receiveWithCanonicalZone(newCanonicalZone))

        case UpdateMemberCommand(_, memberId, member) =>

          if (!canModify(canonicalZone, memberId, publicKey)) {

            sender !
              ResponseWithId(
                ErrorResponse(
                  JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                  "Member modification forbidden",
                  None
                ),
                id
              )

            log.warning(s"Received invalid command from ${publicKey.fingerprint} to update $memberId")

          } else {

            val timestamp = System.currentTimeMillis

            sender !
              ResponseWithId(
                UpdateMemberResponse,
                id
              )

            val newCanonicalZone = canonicalZone.copy(
              members = canonicalZone.members + (memberId -> member),
              lastModified = timestamp
            )
            val memberUpdatedNotification = MemberUpdatedNotification(zoneId, timestamp, memberId, member)
            presentClients.keys.foreach(_ ! memberUpdatedNotification)

            context.become(receiveWithCanonicalZone(newCanonicalZone))

          }

        case CreateAccountCommand(_, account) =>

          def freshAccountId: AccountId = {
            val accountId = AccountId.generate
            if (!canonicalZone.accounts.contains(accountId)) {
              accountId
            } else {
              freshAccountId
            }
          }
          val accountId = freshAccountId

          val timestamp = System.currentTimeMillis

          sender !
            ResponseWithId(
              CreateAccountResponse(
                accountId
              ),
              id
            )

          val newCanonicalZone = canonicalZone.copy(
            accounts = canonicalZone.accounts + (accountId -> account),
            lastModified = timestamp
          )
          val accountCreatedNotification = AccountCreatedNotification(zoneId, timestamp, accountId, account)
          presentClients.keys.foreach(_ ! accountCreatedNotification)

          context.become(receiveWithCanonicalZone(newCanonicalZone))

        case UpdateAccountCommand(_, accountId, account) =>

          if (!canModify(canonicalZone, accountId, publicKey)) {

            sender !
              ResponseWithId(
                ErrorResponse(
                  JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                  "Account modification forbidden",
                  None
                ),
                id
              )

            log.warning(s"Received invalid command from ${publicKey.fingerprint} to update $accountId")

          } else {

            val timestamp = System.currentTimeMillis

            sender !
              ResponseWithId(
                UpdateAccountResponse,
                id
              )

            val newCanonicalZone = canonicalZone.copy(
              accounts = canonicalZone.accounts + (accountId -> account),
              lastModified = timestamp
            )
            val accountUpdatedNotification = AccountUpdatedNotification(zoneId, timestamp, accountId, account)
            presentClients.keys.foreach(_ ! accountUpdatedNotification)

            context.become(receiveWithCanonicalZone(newCanonicalZone))

          }

        case AddTransactionCommand(_, description, from, to, amount: BigDecimal) =>

          if (!canModify(canonicalZone, from, publicKey)) {

            sender !
              ResponseWithId(
                ErrorResponse(
                  JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                  "Account modification forbidden",
                  None
                ),
                id
              )

            log.warning(s"Received invalid command from ${publicKey.fingerprint} to add transaction on $from")

          } else {

            val timestamp = System.currentTimeMillis

            val transaction = Transaction(description, from, to, amount, timestamp)

            val eitherErrorOrUpdatedAccountBalances = Zone.checkAndUpdateBalances(
              transaction,
              canonicalZone,
              accountBalances
            )

            eitherErrorOrUpdatedAccountBalances match {

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

              case Right(updatedAccountBalances) =>

                accountBalances = updatedAccountBalances

                def freshTransactionId: TransactionId = {
                  val transactionId = TransactionId.generate
                  if (!canonicalZone.transactions.contains(transactionId)) {
                    transactionId
                  } else {
                    freshTransactionId
                  }
                }
                val transactionId = freshTransactionId

                sender !
                  ResponseWithId(
                    AddTransactionResponse(
                      transactionId,
                      transaction.created
                    ),
                    id
                  )

                val newCanonicalZone = canonicalZone.copy(
                  transactions = canonicalZone.transactions + (transactionId -> transaction),
                  lastModified = transaction.created
                )
                val transactionAddedNotification = TransactionAddedNotification(
                  zoneId,
                  timestamp,
                  transactionId,
                  transaction
                )
                presentClients.keys.foreach(_ ! transactionAddedNotification)

                context.become(receiveWithCanonicalZone(newCanonicalZone))

            }

          }

      }

    case Terminated(clientConnection) =>

      handleQuit(clientConnection)

  }

}