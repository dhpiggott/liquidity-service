package actors

import java.math.BigInteger

import actors.ClientConnection.AuthenticatedInboundMessage
import actors.ZoneValidatorManager.TerminationRequest
import akka.actor._
import controllers.Application.PublicKey
import models._

object ZoneValidator {

  def props(zoneId: ZoneId) = Props(new ZoneValidator(zoneId))

}

class ZoneValidator(zoneId: ZoneId) extends Actor with ActorLogging {

  var presentClients = Set.empty[ActorRef]

  var maybeCanonicalZone: Option[Zone] = None

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

  def receive = {

    case Terminated(clientConnection) =>

      handleQuit(clientConnection)

    case AuthenticatedInboundMessage(publicKey, inboundMessage) =>

      inboundMessage match {

        case CreateZone(name, zoneType) =>

          handleJoin(sender())

          sender ! ZoneCreated(zoneId)

          if (maybeCanonicalZone.isEmpty) {

            maybeCanonicalZone = Some(Zone(name, zoneType))

            val zoneState = ZoneState(zoneId, maybeCanonicalZone.get)
            presentClients.foreach(_ ! zoneState)

          }

        case JoinZone(_) =>

          sender ! maybeCanonicalZone.fold[OutboundZoneMessage](ZoneEmpty(zoneId))(ZoneState(zoneId, _))

          if (maybeCanonicalZone.isDefined) {

            val canonicalZone = maybeCanonicalZone.get

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

          }

        case QuitZone(_) =>

          if (maybeCanonicalZone.isDefined) {

            val canonicalZone = maybeCanonicalZone.get

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

          }

        case SetZoneName(_, name) =>

          if (maybeCanonicalZone.isDefined) {

            val canonicalZone = maybeCanonicalZone.get

            maybeCanonicalZone = Some(canonicalZone.copy(name = name))
            val zoneState = ZoneState(zoneId, maybeCanonicalZone.get)
            presentClients.foreach(_ ! zoneState)

          }

        case CreateMember(_, member) =>

          // TODO: Maximum numbers?

          if (maybeCanonicalZone.isDefined) {

            val canonicalZone = maybeCanonicalZone.get

            def freshMemberId: MemberId = {
              val memberId = MemberId()
              if (canonicalZone.members.get(memberId).isEmpty) {
                memberId
              } else {
                freshMemberId
              }
            }
            val memberId = freshMemberId

            maybeCanonicalZone = Some(
              canonicalZone.copy(members = canonicalZone.members + (memberId -> member))
            )
            val zoneState = ZoneState(zoneId, maybeCanonicalZone.get)
            presentClients.foreach(_ ! zoneState)

            memberIdsForIdentity(maybeCanonicalZone.get, publicKey).foreach { memberId =>
              val memberJoinedZone = MemberJoinedZone(zoneId, memberId)
              presentClients.foreach(_ ! memberJoinedZone)
            }

          }

        case UpdateMember(_, memberId, member) =>

          if (maybeCanonicalZone.isDefined) {

            val canonicalZone = maybeCanonicalZone.get

            if (!canModify(canonicalZone, memberId, publicKey)) {

              log.warning(s"Received invalid request from ${publicKey.fingerprint} to update $memberId")

            } else {

              maybeCanonicalZone = Some(
                canonicalZone.copy(members = canonicalZone.members + (memberId -> member))
              )
              val zoneState = ZoneState(zoneId, maybeCanonicalZone.get)
              presentClients.foreach(_ ! zoneState)

            }

          }

        case DeleteMember(_, memberId) =>

          if (maybeCanonicalZone.isDefined) {

            val canonicalZone = maybeCanonicalZone.get

            if (!canModify(canonicalZone, memberId, publicKey) || !canDelete(canonicalZone, memberId)) {

              log.warning(s"Received invalid request from ${publicKey.fingerprint} to delete $memberId")

            } else {

              maybeCanonicalZone = Some(
                canonicalZone.copy(members = canonicalZone.members - memberId)
              )
              val zoneState = ZoneState(zoneId, maybeCanonicalZone.get)
              presentClients.foreach(_ ! zoneState)

              val memberIds = maybeCanonicalZone.map(_.members.filter(_._2.publicKey == publicKey).map(_._1))
              memberIds.toList.flatten.foreach { memberId =>
                val memberQuitZone = MemberQuitZone(zoneId, memberId)
                presentClients.foreach(_ ! memberQuitZone)
              }

            }

          }

        case CreateAccount(_, account) =>

          if (maybeCanonicalZone.isDefined) {

            val canonicalZone = maybeCanonicalZone.get

            def freshAccountId: AccountId = {
              val accountId = AccountId()
              if (canonicalZone.accounts.get(accountId).isEmpty) {
                accountId
              } else {
                freshAccountId
              }
            }
            val accountId = freshAccountId

            maybeCanonicalZone = Some(
              canonicalZone.copy(accounts = canonicalZone.accounts + (accountId -> account))
            )
            val zoneState = ZoneState(zoneId, maybeCanonicalZone.get)
            presentClients.foreach(_ ! zoneState)

          }

        case UpdateAccount(_, accountId, account) =>

          if (maybeCanonicalZone.isDefined) {

            val canonicalZone = maybeCanonicalZone.get

            if (!canModify(canonicalZone, accountId, publicKey)) {

              log.warning(s"Received invalid request from ${publicKey.fingerprint} to update $accountId")

            } else {

              maybeCanonicalZone = Some(
                canonicalZone.copy(accounts = canonicalZone.accounts + (accountId -> account))
              )
              val zoneState = ZoneState(zoneId, maybeCanonicalZone.get)
              presentClients.foreach(_ ! zoneState)

            }

          }

        case DeleteAccount(_, accountId) =>

          if (maybeCanonicalZone.isDefined) {

            val canonicalZone = maybeCanonicalZone.get

            if (!canModify(canonicalZone, accountId, publicKey) || !canDelete(canonicalZone, accountId)) {

              log.warning(s"Received invalid request from ${publicKey.fingerprint} to delete $accountId")

            } else {

              maybeCanonicalZone = Some(
                canonicalZone.copy(accounts = canonicalZone.accounts - accountId)
              )
              val zoneState = ZoneState(zoneId, maybeCanonicalZone.get)
              presentClients.foreach(_ ! zoneState)

            }

          }

        case AddTransaction(_, transaction) =>

          if (maybeCanonicalZone.isDefined) {

            val canonicalZone = maybeCanonicalZone.get

            if (!canModify(canonicalZone, transaction.from, publicKey)
              || !checkTransactionAndUpdateAccountBalances(canonicalZone, transaction)) {

              log.warning(s"Received invalid request from ${publicKey.fingerprint} to add $transaction")

            } else {

              maybeCanonicalZone = Some(
                canonicalZone.copy(transactions = canonicalZone.transactions :+ transaction)
              )
              val zoneState = ZoneState(zoneId, maybeCanonicalZone.get)
              presentClients.foreach(_ ! zoneState)

            }

          }

        case RestoreZone(_, zone) =>

          val canonicalZoneLastModified = maybeCanonicalZone.fold(0L)(_.lastModified)

          if (zone.lastModified <= canonicalZoneLastModified) {

            log.info(s"Received request from ${publicKey.fingerprint} to restore outdated $zone")

          } else {

            maybeCanonicalZone = Some(zone)
            val zoneState = ZoneState(zoneId, maybeCanonicalZone.get)
            presentClients.foreach(_ ! zoneState)

          }

      }

  }

}