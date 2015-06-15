package actors

import actors.ClientConnection.AuthenticatedCommand
import akka.actor._
import com.dhpcs.jsonrpc.JsonRpcResponseError
import com.dhpcs.liquidity.models._

object ZoneValidator {

  def props(zoneId: ZoneId) = Props(new ZoneValidator(zoneId))

}

class ZoneValidator(zoneId: ZoneId) extends Actor with ActorLogging {

  // TODO?
  var presentClients = Map.empty[ActorRef, PublicKey]
  var accountBalances = Map.empty[AccountId, BigDecimal].withDefaultValue(BigDecimal(0))

  def canDelete(zone: Zone, memberId: MemberId) =
    !zone.accounts.values.exists { account =>
      account.owners.contains(memberId)
    }

  def canDelete(zone: Zone, accountId: AccountId) =
    !zone.transactions.values.exists { transaction =>
      transaction.from == accountId || transaction.to == accountId
    }

  def canModify(zone: Zone, memberId: MemberId, publicKey: PublicKey) =
    zone.members.get(memberId).fold(false)(_.publicKey == publicKey)

  def canModify(zone: Zone, accountId: AccountId, publicKey: PublicKey) =
    zone.accounts.get(accountId).fold(false)(
      _.owners.exists { memberId =>
        zone.members.get(memberId).fold(false)(_.publicKey == publicKey)
      }
    )

  def handleJoin(clientConnection: ActorRef, publicKey: PublicKey): Unit = {
    context.watch(sender())
    val wasAlreadyPresent = presentClients.values.exists(_ == publicKey)
    presentClients += (clientConnection -> publicKey)
    if (!wasAlreadyPresent) {
      val clientJoinedZoneNotification = ClientJoinedZoneNotification(zoneId, publicKey)
      presentClients.keys.foreach(_ ! clientJoinedZoneNotification)
    }
    log.debug(s"${presentClients.size} clients are present")
  }

  def handleQuit(clientConnection: ActorRef): Unit = {
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

    case AuthenticatedCommand(publicKey, command, id) =>

      command match {

        case CreateZoneCommand(name, zoneType) =>

          val zone = Zone(name, zoneType)

          sender !
            (CreateZoneResponse(zoneId), id)

          context.become(receiveWithCanonicalZone(zone))

        case _ =>

          sender !
            (ErrorResponse(
              JsonRpcResponseError.ReservedErrorCodeFloor - 1,
              "Zone does not exist",
              None),
              id)

          log.warning(s"Received command from ${publicKey.fingerprint} to operate on non-existing zone")

      }

    case Terminated(clientConnection) =>

      handleQuit(clientConnection)

  }

  def receiveWithCanonicalZone(canonicalZone: Zone): Receive = {

    case AuthenticatedCommand(publicKey, command, id) =>

      command match {

        case CreateZoneCommand(name, zoneType) =>

          sender !
            (ErrorResponse(
              JsonRpcResponseError.ReservedErrorCodeFloor - 1,
              "Zone already exists",
              None),
              id)

          log.warning(s"Received command from ${publicKey.fingerprint} to create already existing zone")

        case JoinZoneCommand(_) =>

          sender !
            (JoinZoneResponse(canonicalZone, presentClients.values.toSet), id)

          if (!presentClients.contains(sender())) {

            handleJoin(sender(), publicKey)

          }

        case QuitZoneCommand(_) =>

          sender !
            (QuitZoneResponse, id)

          if (presentClients.contains(sender())) {

            handleQuit(sender())

          }

        case SetZoneNameCommand(_, name) =>

          val timestamp = System.currentTimeMillis

          sender !
            (SetZoneNameResponse, id)

          val newCanonicalZone = canonicalZone.copy(
            name = name,
            lastModified = timestamp
          )
          val zoneNameSetNotification = ZoneNameSetNotification(zoneId, timestamp, name)
          presentClients.keys.foreach(_ ! zoneNameSetNotification)

          context.become(receiveWithCanonicalZone(newCanonicalZone))

        case CreateMemberCommand(_, member) =>

          // TODO: Maximum numbers?

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
            (CreateMemberResponse(memberId), id)

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
              (ErrorResponse(
                JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                "Member modification forbidden",
                None),
                id)

            log.warning(s"Received invalid command from ${publicKey.fingerprint} to update $memberId")

          } else {

            val timestamp = System.currentTimeMillis

            sender !
              (UpdateMemberResponse, id)

            val newCanonicalZone = canonicalZone.copy(
              members = canonicalZone.members + (memberId -> member),
              lastModified = timestamp
            )
            val memberUpdatedNotification = MemberUpdatedNotification(zoneId, timestamp, memberId, member)
            presentClients.keys.foreach(_ ! memberUpdatedNotification)

            context.become(receiveWithCanonicalZone(newCanonicalZone))

          }

        case DeleteMemberCommand(_, memberId) =>

          if (!canModify(canonicalZone, memberId, publicKey) || !canDelete(canonicalZone, memberId)) {

            sender !
              (ErrorResponse(
                JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                "Member deletion forbidden",
                None),
                id)

            log.warning(s"Received invalid command from ${publicKey.fingerprint} to delete $memberId")

          } else {

            val timestamp = System.currentTimeMillis

            sender !
              (DeleteMemberResponse, id)

            val newCanonicalZone = canonicalZone.copy(
              members = canonicalZone.members - memberId,
              lastModified = timestamp
            )
            val memberDeletedNotification = MemberDeletedNotification(zoneId, timestamp, memberId)
            presentClients.keys.foreach(_ ! memberDeletedNotification)

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
            (CreateAccountResponse(accountId), id)

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
              (ErrorResponse(
                JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                "Account modification forbidden",
                None),
                id)

            log.warning(s"Received invalid command from ${publicKey.fingerprint} to update $accountId")

          } else {

            val timestamp = System.currentTimeMillis

            sender !
              (UpdateAccountResponse, id)

            val newCanonicalZone = canonicalZone.copy(
              accounts = canonicalZone.accounts + (accountId -> account),
              lastModified = timestamp
            )
            val accountUpdatedNotification = AccountUpdatedNotification(zoneId, timestamp, accountId, account)
            presentClients.keys.foreach(_ ! accountUpdatedNotification)

            context.become(receiveWithCanonicalZone(newCanonicalZone))

          }

        case DeleteAccountCommand(_, accountId) =>

          if (!canModify(canonicalZone, accountId, publicKey) || !canDelete(canonicalZone, accountId)) {

            sender !
              (ErrorResponse(
                JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                "Account deletion forbidden",
                None),
                id)

            log.warning(s"Received invalid command from ${publicKey.fingerprint} to delete $accountId")

          } else {

            val timestamp = System.currentTimeMillis

            sender !
              (DeleteAccountResponse, id)

            val newCanonicalZone = canonicalZone.copy(
              accounts = canonicalZone.accounts - accountId,
              lastModified = timestamp
            )
            val accountDeletedNotification = AccountDeletedNotification(zoneId, timestamp, accountId)
            presentClients.keys.foreach(_ ! accountDeletedNotification)

            context.become(receiveWithCanonicalZone(newCanonicalZone))

          }

        case AddTransactionCommand(_, transaction) =>

          if (!canModify(canonicalZone, transaction.from, publicKey)) {

            sender !
              (ErrorResponse(
                JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                "Account modification forbidden",
                None),
                id)

            log.warning(s"Received invalid command from ${publicKey.fingerprint} to add $transaction")

          } else {

            val eitherErrorOrUpdatedAccountBalances = Zone.checkTransactionAndUpdateAccountBalances(
              canonicalZone,
              transaction,
              accountBalances
            )

            eitherErrorOrUpdatedAccountBalances match {

              case Left(message) =>

                sender !
                  (ErrorResponse(
                    JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                    message,
                    None),
                    id)

                log.warning(s"Received invalid command from ${publicKey.fingerprint} to add $transaction")

              case Right(updatedAccountBalances) =>

                val timestamp = System.currentTimeMillis

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
                  (AddTransactionResponse(transactionId), id)

                val newCanonicalZone = canonicalZone.copy(
                  transactions = canonicalZone.transactions + (transactionId -> transaction),
                  lastModified = timestamp
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