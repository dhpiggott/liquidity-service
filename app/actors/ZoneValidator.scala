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

  def canModify(zone: Zone, memberId: MemberId, publicKey: PublicKey) =
    zone.members.get(memberId).fold(false)(_.publicKey == publicKey)

  def canModify(zone: Zone, accountId: AccountId, publicKey: PublicKey) =
    zone.accounts.get(accountId).fold(false)(
      _.owners.exists { memberId =>
        zone.members.get(memberId).fold(false)(_.publicKey == publicKey)
      }
    )

  def checkAndUpdateBalances(transaction: Transaction,
                             zone: Zone,
                             balances: Map[AccountId, BigDecimal]): Either[String, Map[AccountId, BigDecimal]] =
    if (!zone.accounts.contains(transaction.from)) {
      Left(s"Invalid transaction source account: ${transaction.from}")
    } else if (!zone.accounts.contains(transaction.to)) {
      Left(s"Invalid transaction destination account: ${transaction.to}")
    } else {
      val newSourceBalance = balances.getOrElse(transaction.from, BigDecimal(0)) - transaction.amount
      if (newSourceBalance < 0 && transaction.from != zone.equityHolderAccountId) {
        Left(s"Illegal transaction amount: ${transaction.amount}")
      } else {
        val newDestinationBalance = balances.getOrElse(transaction.to, BigDecimal(0)) + transaction.amount
        Right(balances
          + (transaction.from -> newSourceBalance)
          + (transaction.to -> newDestinationBalance))
      }
    }

  def handleJoin(clientConnection: ActorRef, publicKey: PublicKey, clientConnections: Map[ActorRef, PublicKey]) = {
    context.watch(clientConnection)
    val wasAlreadyPresent = clientConnections.values.exists(_ == publicKey)
    val newClientConnections = clientConnections + (clientConnection -> publicKey)
    if (!wasAlreadyPresent) {
      val clientJoinedZoneNotification = ClientJoinedZoneNotification(zoneId, publicKey)
      newClientConnections.keys.foreach(_ ! clientJoinedZoneNotification)
    }
    log.debug(s"${newClientConnections.size} clients are present")
    newClientConnections
  }

  def handleQuit(clientConnection: ActorRef, clientConnections: Map[ActorRef, PublicKey]) = {
    context.unwatch(clientConnection)
    val publicKey = clientConnections(clientConnection)
    val newClientConnections = clientConnections - clientConnection
    val isStillPresent = newClientConnections.values.exists(_ == publicKey)
    if (!isStillPresent) {
      val clientQuitZoneNotification = ClientQuitZoneNotification(zoneId, publicKey)
      newClientConnections.keys.foreach(_ ! clientQuitZoneNotification)
    }
    if (newClientConnections.nonEmpty) {
      log.debug(s"${newClientConnections.size} clients are present")
      // TODO: Disabled to simplify client testing until persistence is implemented
      //    } else {
      //      log.debug(s"No clients are present; requesting termination")
      //      context.parent ! TerminationRequest
    }
    newClientConnections
  }

  def receive = waitingForZone

  def waitingForZone: Receive = {

    case AuthenticatedCommandWithId(publicKey, command, id) =>

      command match {

        case CreateZoneCommand(name, zoneType, equityHolderMember, equityHolderAccount) =>

          val equityHolderMemberId = MemberId.generate
          val equityHolderAccountId = AccountId.generate

          val zone = Zone(
            name,
            zoneType,
            equityHolderMemberId,
            equityHolderAccountId,
            Map(
              equityHolderMemberId -> equityHolderMember
            ),
            Map(
              equityHolderAccountId ->
                equityHolderAccount.copy(
                  owners = Set(equityHolderMemberId)
                )
            ),
            Map.empty,
            System.currentTimeMillis
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

          context.become(
            withZone(
              zone,
              Map.empty[AccountId, BigDecimal],
              Map.empty[ActorRef, PublicKey]
            )
          )

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

  }

  def withZone(zone: Zone,
               balances: Map[AccountId, BigDecimal],
               clientConnections: Map[ActorRef, PublicKey]): Receive = {

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
                zone,
                clientConnections.values.toSet
              ),
              id
            )

          if (!clientConnections.contains(sender())) {

            val newClientConnections = handleJoin(sender(), publicKey, clientConnections)

            context.become(
              withZone(
                zone,
                balances,
                newClientConnections
              )
            )

          }

        case _: QuitZoneCommand =>

          sender !
            ResponseWithId(
              QuitZoneResponse,
              id
            )

          if (clientConnections.contains(sender())) {

            val newClientConnections = handleQuit(sender(), clientConnections)

            context.become(
              withZone(
                zone,
                balances,
                newClientConnections
              )
            )

          }

        case SetZoneNameCommand(_, name) =>

          sender !
            ResponseWithId(
              SetZoneNameResponse,
              id
            )

          val newCanonicalZone = zone.copy(
            name = name
          )
          val zoneNameSetNotification = ZoneNameSetNotification(zoneId, name)
          clientConnections.keys.foreach(_ ! zoneNameSetNotification)

          context.become(withZone(newCanonicalZone, balances, clientConnections))

        case CreateMemberCommand(_, member) =>

          def freshMemberId: MemberId = {
            val memberId = MemberId.generate
            if (!zone.members.contains(memberId)) {
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

          val newCanonicalZone = zone.copy(
            members = zone.members + (memberId -> member)
          )
          val memberCreatedNotification = MemberCreatedNotification(zoneId, memberId, member)
          clientConnections.keys.foreach(_ ! memberCreatedNotification)

          context.become(withZone(newCanonicalZone, balances, clientConnections))

        case UpdateMemberCommand(_, memberId, member) =>

          if (!canModify(zone, memberId, publicKey)) {

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

            sender !
              ResponseWithId(
                UpdateMemberResponse,
                id
              )

            val newCanonicalZone = zone.copy(
              members = zone.members + (memberId -> member)
            )
            val memberUpdatedNotification = MemberUpdatedNotification(zoneId, memberId, member)
            clientConnections.keys.foreach(_ ! memberUpdatedNotification)

            context.become(withZone(newCanonicalZone, balances, clientConnections))

          }

        case CreateAccountCommand(_, account) =>

          def freshAccountId: AccountId = {
            val accountId = AccountId.generate
            if (!zone.accounts.contains(accountId)) {
              accountId
            } else {
              freshAccountId
            }
          }
          val accountId = freshAccountId

          sender !
            ResponseWithId(
              CreateAccountResponse(
                accountId
              ),
              id
            )

          val newCanonicalZone = zone.copy(
            accounts = zone.accounts + (accountId -> account)
          )
          val accountCreatedNotification = AccountCreatedNotification(zoneId, accountId, account)
          clientConnections.keys.foreach(_ ! accountCreatedNotification)

          context.become(withZone(newCanonicalZone, balances, clientConnections))

        case UpdateAccountCommand(_, accountId, account) =>

          if (!canModify(zone, accountId, publicKey)) {

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

            sender !
              ResponseWithId(
                UpdateAccountResponse,
                id
              )

            val newCanonicalZone = zone.copy(
              accounts = zone.accounts + (accountId -> account)
            )
            val accountUpdatedNotification = AccountUpdatedNotification(zoneId, accountId, account)
            clientConnections.keys.foreach(_ ! accountUpdatedNotification)

            context.become(withZone(newCanonicalZone, balances, clientConnections))

          }

        case AddTransactionCommand(_, description, from, to, amount: BigDecimal) =>

          if (!canModify(zone, from, publicKey)) {

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

            val transaction = Transaction(
              description,
              from,
              to,
              amount,
              System.currentTimeMillis
            )

            val eitherErrorOrUpdatedAccountBalances = checkAndUpdateBalances(
              transaction,
              zone,
              balances
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

                def freshTransactionId: TransactionId = {
                  val transactionId = TransactionId.generate
                  if (!zone.transactions.contains(transactionId)) {
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

                val newCanonicalZone = zone.copy(
                  transactions = zone.transactions + (transactionId -> transaction)
                )
                val transactionAddedNotification = TransactionAddedNotification(
                  zoneId,
                  transactionId,
                  transaction
                )
                clientConnections.keys.foreach(_ ! transactionAddedNotification)

                context.become(withZone(newCanonicalZone, updatedAccountBalances, clientConnections))

            }

          }

      }

    case Terminated(clientConnection) =>

      val newClientConnections = handleQuit(clientConnection, clientConnections)

      context.become(
        withZone(
          zone,
          balances,
          newClientConnections
        )
      )

  }

}