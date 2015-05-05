package actors

import java.math.BigInteger

import actors.ClientConnection.AuthenticatedCommand
import actors.ZoneRegistry.TerminationRequest
import akka.actor._
import com.dhpcs.liquidity.models._

object ZoneValidator {

  def props(zoneId: ZoneId) = Props(new ZoneValidator(zoneId))

}

class ZoneValidator(zoneId: ZoneId) extends Actor with ActorLogging {

  var presentClients = Set.empty[ActorRef]

  var accountBalances = Map.empty[AccountId, BigDecimal].withDefaultValue(BigDecimal(BigInteger.ZERO))

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
      if (newSourceBalance.<(BigDecimal(BigInteger.ZERO))) {
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

  def handleJoin(clientConnection: ActorRef): Unit = {
    context.watch(sender())
    presentClients += clientConnection
    log.debug(s"$presentClients clients are present")
  }

  def handleQuit(clientConnection: ActorRef): Unit = {
    context.unwatch(sender())
    presentClients -= clientConnection
    if (presentClients.size != 0) {
      log.debug(s"$presentClients clients are present")
    } else {
      log.debug(s"No clients are present; requesting termination")
      context.parent ! TerminationRequest
    }
  }

  def memberIdsForIdentity(zone: Zone, publicKey: PublicKey) =
    zone.members.collect { case (memberId, member) if member.publicKey == publicKey => memberId }

  def receive = waitingForCanonicalZone

  def waitingForCanonicalZone: Receive = {

    case AuthenticatedCommand(publicKey, command) =>

      command match {

        case CreateZone(name, zoneType) =>

          handleJoin(sender())

          val zone = Zone(name, zoneType)

          sender ! ZoneCreated(zoneId)

          val zoneState = ZoneState(zoneId, zone)
          presentClients.foreach(_ ! zoneState)

          context.become(receiveWithCanonicalZone(zone))

        case JoinZone(_) =>

          sender ! ZoneEmpty(zoneId)

          if (!presentClients.contains(sender())) {

            handleJoin(sender())

          }

        case RestoreZone(_, zone) =>

          val zoneState = ZoneState(zoneId, zone)
          presentClients.foreach(_ ! zoneState)

          context.become(receiveWithCanonicalZone(zone))

        case QuitZone(_) =>

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

    case AuthenticatedCommand(publicKey, command) =>

      command match {

        case CreateZone(name, zoneType) =>

          log.warning(s"Received command from ${publicKey.fingerprint} to create already existing zone")

        case JoinZone(_) =>

          sender ! ZoneState(zoneId, canonicalZone)

          /*
           * In case the sender didn't receive the confirmation the first time around.
           */
          if (presentClients.contains(sender())) {

            memberIdsForIdentity(canonicalZone, publicKey).foreach { memberId =>
              val memberJoinedZone = MemberJoinedZone(zoneId, memberId)
              sender ! memberJoinedZone
            }

          } else {

            handleJoin(sender())

            memberIdsForIdentity(canonicalZone, publicKey).foreach { memberId =>
              val memberJoinedZone = MemberJoinedZone(zoneId, memberId)
              presentClients.foreach(_ ! memberJoinedZone)
            }

          }

        case RestoreZone(_, zone) =>

          if (zone.lastModified <= canonicalZone.lastModified) {

            log.info(s"Received command from ${publicKey.fingerprint} to restore outdated $zone")

          } else {

            val zoneState = ZoneState(zoneId, zone)
            presentClients.foreach(_ ! zoneState)

            context.become(receiveWithCanonicalZone(zone))

          }

        case QuitZone(_) =>

          /*
           * In case the sender didn't receive the confirmation the first time around.
           */
          if (!presentClients.contains(sender())) {

            memberIdsForIdentity(canonicalZone, publicKey).foreach { memberId =>
              val memberQuitZone = MemberQuitZone(zoneId, memberId)
              sender ! memberQuitZone
            }

          } else {

            handleQuit(sender())

            memberIdsForIdentity(canonicalZone, publicKey).foreach { memberId =>
              val memberQuitZone = MemberQuitZone(zoneId, memberId)
              presentClients.foreach(_ ! memberQuitZone)
            }

          }

        case SetZoneName(_, name) =>

          val newCanonicalZone = canonicalZone.copy(name = name)
          val zoneState = ZoneState(zoneId, newCanonicalZone)
          presentClients.foreach(_ ! zoneState)

          context.become(receiveWithCanonicalZone(newCanonicalZone))

        case CreateMember(_, member) =>

          // TODO: Maximum numbers?

          def freshMemberId: MemberId = {
            val memberId = MemberId()
            if (canonicalZone.members.get(memberId).isEmpty) {
              memberId
            } else {
              freshMemberId
            }
          }
          val memberId = freshMemberId

          val newCanonicalZone = canonicalZone.copy(members = canonicalZone.members + (memberId -> member))
          val zoneState = ZoneState(zoneId, newCanonicalZone)
          presentClients.foreach(_ ! zoneState)

          memberIdsForIdentity(newCanonicalZone, publicKey).foreach { memberId =>
            val memberJoinedZone = MemberJoinedZone(zoneId, memberId)
            presentClients.foreach(_ ! memberJoinedZone)
          }

          context.become(receiveWithCanonicalZone(newCanonicalZone))

        case UpdateMember(_, memberId, member) =>

          if (!canModify(canonicalZone, memberId, publicKey)) {

            log.warning(s"Received invalid command from ${publicKey.fingerprint} to update $memberId")

          } else {

            val newCanonicalZone = canonicalZone.copy(members = canonicalZone.members + (memberId -> member))
            val zoneState = ZoneState(zoneId, newCanonicalZone)
            presentClients.foreach(_ ! zoneState)

            context.become(receiveWithCanonicalZone(newCanonicalZone))

          }

        case DeleteMember(_, memberId) =>

          if (!canModify(canonicalZone, memberId, publicKey) || !canDelete(canonicalZone, memberId)) {

            log.warning(s"Received invalid command from ${publicKey.fingerprint} to delete $memberId")

          } else {

            val newCanonicalZone = canonicalZone.copy(members = canonicalZone.members - memberId)
            val zoneState = ZoneState(zoneId, newCanonicalZone)
            presentClients.foreach(_ ! zoneState)

            memberIdsForIdentity(newCanonicalZone, publicKey).foreach { memberId =>
              val memberQuitZone = MemberQuitZone(zoneId, memberId)
              presentClients.foreach(_ ! memberQuitZone)
            }

            context.become(receiveWithCanonicalZone(newCanonicalZone))

          }

        case CreateAccount(_, account) =>

          def freshAccountId: AccountId = {
            val accountId = AccountId()
            if (canonicalZone.accounts.get(accountId).isEmpty) {
              accountId
            } else {
              freshAccountId
            }
          }
          val accountId = freshAccountId

          val newCanonicalZone = canonicalZone.copy(accounts = canonicalZone.accounts + (accountId -> account))
          val zoneState = ZoneState(zoneId, newCanonicalZone)
          presentClients.foreach(_ ! zoneState)

          context.become(receiveWithCanonicalZone(newCanonicalZone))

        case UpdateAccount(_, accountId, account) =>

          if (!canModify(canonicalZone, accountId, publicKey)) {

            log.warning(s"Received invalid command from ${publicKey.fingerprint} to update $accountId")

          } else {

            val newCanonicalZone = canonicalZone.copy(accounts = canonicalZone.accounts + (accountId -> account))
            val zoneState = ZoneState(zoneId, newCanonicalZone)
            presentClients.foreach(_ ! zoneState)

            context.become(receiveWithCanonicalZone(newCanonicalZone))

          }

        case DeleteAccount(_, accountId) =>

          if (!canModify(canonicalZone, accountId, publicKey) || !canDelete(canonicalZone, accountId)) {

            log.warning(s"Received invalid command from ${publicKey.fingerprint} to delete $accountId")

          } else {

            val newCanonicalZone = canonicalZone.copy(accounts = canonicalZone.accounts - accountId)
            val zoneState = ZoneState(zoneId, newCanonicalZone)
            presentClients.foreach(_ ! zoneState)

            context.become(receiveWithCanonicalZone(newCanonicalZone))

          }

        case AddTransaction(_, transaction) =>

          if (!canModify(canonicalZone, transaction.from, publicKey)
            || !checkTransactionAndUpdateAccountBalances(canonicalZone, transaction)) {

            log.warning(s"Received invalid command from ${publicKey.fingerprint} to add $transaction")

          } else {

            val newCanonicalZone = canonicalZone.copy(transactions = canonicalZone.transactions :+ transaction)
            val zoneState = ZoneState(zoneId, newCanonicalZone)
            presentClients.foreach(_ ! zoneState)

            context.become(receiveWithCanonicalZone(newCanonicalZone))

          }

      }

    case Terminated(clientConnection) =>

      handleQuit(clientConnection)

  }

}