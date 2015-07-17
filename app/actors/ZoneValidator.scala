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
    zone.members.get(memberId).fold[Either[String, Unit]](Left("Member does not exist"))(member =>
      if (publicKey != member.publicKey) {
        Left("Client's public key does not match Member's public key")
      } else {
        Right(())
      }
    )

  def canModify(zone: Zone, accountId: AccountId, publicKey: PublicKey) =
    zone.accounts.get(accountId).fold[Either[String, Unit]](Left("Account does not exist"))(account =>
      if (!account.owners.exists(memberId =>
        zone.members.get(memberId).fold(false)(publicKey == _.publicKey)
      )) {
        Left("Client's public key does not match that of any account owner member")
      } else {
        Right(())
      }
    )

  def canModify(zone: Zone, accountId: AccountId, actingAs: MemberId, publicKey: PublicKey) =
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

  def checkAccountOwners(zone: Zone, account: Account) = {
    val invalidAccountOwners = account.owners -- zone.members.keys
    if (invalidAccountOwners.nonEmpty) {
      Left(s"Invalid account owners: $invalidAccountOwners")
    } else {
      Right(())
    }
  }

  def checkAndUpdateBalances(transaction: Transaction,
                             zone: Zone,
                             balances: Map[AccountId, BigDecimal]): Either[String, Map[AccountId, BigDecimal]] =
    if (!zone.accounts.contains(transaction.from)) {
      Left(s"Invalid transaction source account: ${transaction.from}")
    } else if (!zone.accounts.contains(transaction.to)) {
      Left(s"Invalid transaction destination account: ${transaction.to}")
    } else {
      val updatedSourceBalance = balances(transaction.from) - transaction.value
      if (updatedSourceBalance < 0 && transaction.from != zone.equityAccountId) {
        Left(s"Illegal transaction value: ${transaction.value}")
      } else {
        val updatedDestinationBalance = balances(transaction.to) + transaction.value
        Right(balances +
          (transaction.from -> updatedSourceBalance) +
          (transaction.to -> updatedDestinationBalance))
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

        case CreateZoneCommand(name, equityOwner, equityAccount, metadata) =>

          val equityOwnerId = MemberId.generate
          val equityAccountId = AccountId.generate

          val zone = Zone(
            name,
            equityAccountId,
            Map(
              equityOwnerId -> equityOwner
            ),
            Map(
              equityAccountId ->
                equityAccount.copy(
                  owners = Set(equityOwnerId)
                )
            ),
            Map.empty,
            System.currentTimeMillis,
            metadata
          )

          sender !
            ResponseWithId(
              CreateZoneResponse(
                zoneId,
                equityOwnerId,
                equityAccountId
              ),
              id
            )

          context.become(
            withZone(
              zone,
              Map.empty[AccountId, BigDecimal].withDefaultValue(BigDecimal(0)),
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
                clientConnections.values.toSet + publicKey
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

          canModify(zone, memberId, publicKey) match {

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

          checkAccountOwners(zone, account) match {

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

          }

        case UpdateAccountCommand(_, accountId, account) =>

          canModify(zone, accountId, publicKey) match {

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

              checkAccountOwners(zone, account) match {

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

          }

        case AddTransactionCommand(_, actingAs, description, from, to, value, metadata) =>

          canModify(zone, from, actingAs, publicKey) match {

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

              val transaction = Transaction(
                description,
                from,
                to,
                value,
                actingAs,
                System.currentTimeMillis,
                metadata
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
