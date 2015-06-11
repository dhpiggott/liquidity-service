package actors

import actors.ClientConnection.AuthenticatedCommand
import actors.ZoneRegistry.TerminationRequest
import akka.actor._
import com.dhpcs.jsonrpc.JsonRpcResponseError
import com.dhpcs.liquidity.models._

object ZoneValidator {

  def props(zoneId: ZoneId) = Props(new ZoneValidator(zoneId))

}

class ZoneValidator(zoneId: ZoneId) extends Actor with ActorLogging {

  var presentClients = Map.empty[ActorRef, PublicKey]
  var accountBalances = Map.empty[AccountId, BigDecimal].withDefaultValue(BigDecimal(0))

  def canDelete(zone: Zone, memberId: MemberId) =
    !zone.accounts.values.exists { account =>
      account.owners.contains(memberId)
    }

  def canDelete(zone: Zone, accountId: AccountId) =
    !zone.transactions.exists { transaction =>
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

  def checkTransactionAndUpdateAccountBalances(zone: Zone, transaction: Transaction) = {
    if (!zone.accounts.contains(transaction.from)) {
      log.warning(s"Invalid transaction source account: ${transaction.from}")
      false
    } else if (!zone.accounts.contains(transaction.to)) {
      log.warning(s"Invalid transaction destination account: ${transaction.to}")
      false
    } else {

      // TODO: Validate zone payment state for seigniorage, allow negative account values for special account types
      val newSourceBalance = accountBalances(transaction.from).-(transaction.amount)
      if (newSourceBalance.<(BigDecimal(0))) {
        log.warning(s"Illegal transaction amount: ${transaction.amount}")
        false
      } else {
        val newDestinationBalance = accountBalances(transaction.to).+(transaction.amount)
        accountBalances += transaction.from -> newSourceBalance
        accountBalances += transaction.to -> newDestinationBalance
        true
      }
    }
  }

  def handleJoin(clientConnection: ActorRef, publicKey: PublicKey): Unit = {
    context.watch(sender())
    val wasAlreadyPresent = presentClients.values.exists(_ == publicKey)
    presentClients += (clientConnection -> publicKey)
    if (!wasAlreadyPresent) {
      val clientJoinedZone = ClientJoinedZone(zoneId, publicKey)
      presentClients.keys.foreach(_ ! clientJoinedZone)
    }
    log.debug(s"$presentClients clients are present")
  }

  def handleQuit(clientConnection: ActorRef): Unit = {
    context.unwatch(sender())
    val publicKey = presentClients(clientConnection)
    presentClients -= clientConnection
    val isStillPresent = presentClients.values.exists(_ == publicKey)
    if (!isStillPresent) {
      val clientQuitZone = ClientQuitZone(zoneId, publicKey)
      presentClients.keys.foreach(_ ! clientQuitZone)
    }
    if (presentClients.nonEmpty) {
      log.debug(s"$presentClients clients are present")
    } else {
      log.debug(s"No clients are present; requesting termination")
      context.parent ! TerminationRequest
    }
  }

  def receive = waitingForCanonicalZone

  def waitingForCanonicalZone: Receive = {

    case AuthenticatedCommand(publicKey, command, id) =>

      command match {

        case CreateZone(name, zoneType) =>

          handleJoin(sender(), publicKey)

          val zone = Zone(name, zoneType)

          sender !
            (ZoneCreated(zoneId), id)

          val zoneState = ZoneState(zoneId, zone)
          presentClients.keys.foreach(_ ! zoneState)

          context.become(receiveWithCanonicalZone(zone))

        case JoinZone(_) =>

          sender !
            (ZoneJoined(None), id)

          if (!presentClients.contains(sender())) {

            handleJoin(sender(), publicKey)

          }

        case RestoreZone(_, zone) =>

          sender !
            (ZoneRestored, id)

          val zoneState = ZoneState(zoneId, zone)
          presentClients.keys.foreach(_ ! zoneState)

          context.become(receiveWithCanonicalZone(zone))

        case QuitZone(_) =>

          sender !
            (ZoneQuit, id)

          if (presentClients.contains(sender())) {

            handleQuit(sender())

          }

        case _ =>

          log.warning(s"Received command from ${publicKey.fingerprint} to operate on non-existing zone")

      }

    case Terminated(clientConnection) =>

      handleQuit(clientConnection)

  }

  def receiveWithCanonicalZone(canonicalZone: Zone): Receive = {

    case AuthenticatedCommand(publicKey, command, id) =>

      command match {

        case CreateZone(name, zoneType) =>

          sender !
            (CommandErrorResponse(
              JsonRpcResponseError.ReservedErrorCodeFloor - 1,
              "Zone already exists",
              None),
              id)

          log.warning(s"Received command from ${publicKey.fingerprint} to create already existing zone")

        case JoinZone(_) =>

          sender !
            (ZoneJoined(Some(ZoneAndConnectedClients(canonicalZone, presentClients.values.toSet))), id)

          if (!presentClients.contains(sender())) {

            handleJoin(sender(), publicKey)

          }

        case RestoreZone(_, zone) =>

          if (zone.lastModified <= canonicalZone.lastModified) {

            sender !
              (CommandErrorResponse(
                JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                "Zone state is out of date",
                None),
                id)

            log.info(s"Received command from ${publicKey.fingerprint} to restore outdated $zone")

          } else {

            sender !
              (ZoneRestored, id)

            val zoneState = ZoneState(zoneId, zone)
            presentClients.keys.foreach(_ ! zoneState)

            context.become(receiveWithCanonicalZone(zone))

          }

        case QuitZone(_) =>

          sender !
            (ZoneQuit, id)

          if (presentClients.contains(sender())) {

            handleQuit(sender())

          }

        case SetZoneName(_, name) =>

          sender !
            (ZoneNameSet, id)

          val newCanonicalZone = canonicalZone.copy(
            name = name,
            lastModified = System.currentTimeMillis
          )
          val zoneState = ZoneState(zoneId, newCanonicalZone)
          presentClients.keys.foreach(_ ! zoneState)

          context.become(receiveWithCanonicalZone(newCanonicalZone))

        case CreateMember(_, member) =>

          // TODO: Maximum numbers?

          def freshMemberId: MemberId = {
            val memberId = MemberId.generate
            if (canonicalZone.members.get(memberId).isEmpty) {
              memberId
            } else {
              freshMemberId
            }
          }
          val memberId = freshMemberId

          sender !
            (MemberCreated(memberId), id)

          val newCanonicalZone = canonicalZone.copy(
            members = canonicalZone.members + (memberId -> member),
            lastModified = System.currentTimeMillis
          )
          val zoneState = ZoneState(zoneId, newCanonicalZone)
          presentClients.keys.foreach(_ ! zoneState)

          context.become(receiveWithCanonicalZone(newCanonicalZone))

        case UpdateMember(_, memberId, member) =>

          if (!canModify(canonicalZone, memberId, publicKey)) {

            sender !
              (CommandErrorResponse(
                JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                "Member modification forbidden",
                None),
                id)

            log.warning(s"Received invalid command from ${publicKey.fingerprint} to update $memberId")

          } else {

            sender !
              (MemberUpdated, id)

            val newCanonicalZone = canonicalZone.copy(
              members = canonicalZone.members + (memberId -> member),
              lastModified = System.currentTimeMillis
            )
            val zoneState = ZoneState(zoneId, newCanonicalZone)
            presentClients.keys.foreach(_ ! zoneState)

            context.become(receiveWithCanonicalZone(newCanonicalZone))

          }

        case DeleteMember(_, memberId) =>

          if (!canModify(canonicalZone, memberId, publicKey) || !canDelete(canonicalZone, memberId)) {

            sender !
              (CommandErrorResponse(
                JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                "Member deletion forbidden",
                None),
                id)

            log.warning(s"Received invalid command from ${publicKey.fingerprint} to delete $memberId")

          } else {

            sender !
              (MemberDeleted, id)

            val newCanonicalZone = canonicalZone.copy(
              members = canonicalZone.members - memberId,
              lastModified = System.currentTimeMillis
            )
            val zoneState = ZoneState(zoneId, newCanonicalZone)
            presentClients.keys.foreach(_ ! zoneState)

            context.become(receiveWithCanonicalZone(newCanonicalZone))

          }

        case CreateAccount(_, account) =>

          def freshAccountId: AccountId = {
            val accountId = AccountId.generate
            if (canonicalZone.accounts.get(accountId).isEmpty) {
              accountId
            } else {
              freshAccountId
            }
          }
          val accountId = freshAccountId

          sender !
            (AccountCreated, id)

          val newCanonicalZone = canonicalZone.copy(
            accounts = canonicalZone.accounts + (accountId -> account),
            lastModified = System.currentTimeMillis
          )
          val zoneState = ZoneState(zoneId, newCanonicalZone)
          presentClients.keys.foreach(_ ! zoneState)

          context.become(receiveWithCanonicalZone(newCanonicalZone))

        case UpdateAccount(_, accountId, account) =>

          if (!canModify(canonicalZone, accountId, publicKey)) {

            sender !
              (CommandErrorResponse(
                JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                "Account modification forbidden",
                None),
                id)

            log.warning(s"Received invalid command from ${publicKey.fingerprint} to update $accountId")

          } else {

            sender !
              (AccountUpdated, id)

            val newCanonicalZone = canonicalZone.copy(
              accounts = canonicalZone.accounts + (accountId -> account),
              lastModified = System.currentTimeMillis
            )
            val zoneState = ZoneState(zoneId, newCanonicalZone)
            presentClients.keys.foreach(_ ! zoneState)

            context.become(receiveWithCanonicalZone(newCanonicalZone))

          }

        case DeleteAccount(_, accountId) =>

          if (!canModify(canonicalZone, accountId, publicKey) || !canDelete(canonicalZone, accountId)) {

            sender !
              (CommandErrorResponse(
                JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                "Account deletion forbidden",
                None),
                id)

            log.warning(s"Received invalid command from ${publicKey.fingerprint} to delete $accountId")

          } else {

            sender !
              (AccountDeleted, id)

            val newCanonicalZone = canonicalZone.copy(
              accounts = canonicalZone.accounts - accountId,
              lastModified = System.currentTimeMillis
            )
            val zoneState = ZoneState(zoneId, newCanonicalZone)
            presentClients.keys.foreach(_ ! zoneState)

            context.become(receiveWithCanonicalZone(newCanonicalZone))

          }

        case AddTransaction(_, transaction) =>

          if (!canModify(canonicalZone, transaction.from, publicKey)
            || !checkTransactionAndUpdateAccountBalances(canonicalZone, transaction)) {

            sender !
              (CommandErrorResponse(
                JsonRpcResponseError.ReservedErrorCodeFloor - 1,
                "Transaction addition forbidden",
                None),
                id)

            log.warning(s"Received invalid command from ${publicKey.fingerprint} to add $transaction")

          } else {

            sender !
              (TransactionAdded, id)

            val newCanonicalZone = canonicalZone.copy(
              transactions = canonicalZone.transactions :+ transaction,
              lastModified = System.currentTimeMillis
            )
            val zoneState = ZoneState(zoneId, newCanonicalZone)
            presentClients.keys.foreach(_ ! zoneState)

            context.become(receiveWithCanonicalZone(newCanonicalZone))

          }

      }

    case Terminated(clientConnection) =>

      handleQuit(clientConnection)

  }

}