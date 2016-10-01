package com.dhpcs.liquidity.boardgame

import java.util.Currency

import com.dhpcs.jsonrpc.ResponseCompanion.ErrorResponse
import com.dhpcs.liquidity.boardgame.BoardGame._
import com.dhpcs.liquidity.client.ServerConnection
import com.dhpcs.liquidity.client.ServerConnection._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.protocol._
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Try

object BoardGame {

  trait GameDatabase {
    def insertGame(zoneId: ZoneId, created: Long, expires: Long, name: String): Long

    def checkAndUpdateGame(zoneId: ZoneId, name: String): java.lang.Long

    def updateGameName(gameId: Long, name: String): Unit
  }

  sealed trait JoinState

  case object UNAVAILABLE extends JoinState

  case object GENERAL_FAILURE extends JoinState

  case object TLS_ERROR extends JoinState

  case object UNSUPPORTED_VERSION extends JoinState

  case object AVAILABLE extends JoinState

  case object CONNECTING extends JoinState

  case object WAITING_FOR_VERSION_CHECK extends JoinState

  case object CREATING extends JoinState

  case object JOINING extends JoinState

  case object JOINED extends JoinState

  case object QUITTING extends JoinState

  case object DISCONNECTING extends JoinState

  sealed trait Player extends Serializable {
    def zoneId: ZoneId

    def member: Member

    def account: Account

    def isBanker: Boolean
  }

  sealed trait Identity extends Player

  sealed trait Transfer extends Serializable {
    def creator: Either[(MemberId, Member), Player]

    def from: Either[(AccountId, Account), Player]

    def to: Either[(AccountId, Account), Player]

    def transaction: Transaction
  }

  case class PlayerWithBalanceAndConnectionState(zoneId: ZoneId,
                                                 member: Member,
                                                 account: Account,
                                                 balanceWithCurrency:
                                                 (BigDecimal, Option[Either[String, Currency]]),
                                                 isBanker: Boolean,
                                                 isConnected: Boolean) extends Player

  case class IdentityWithBalance(zoneId: ZoneId,
                                 member: Member,
                                 account: Account,
                                 balanceWithCurrency:
                                 (BigDecimal, Option[Either[String, Currency]]),
                                 isBanker: Boolean)
    extends Identity

  case class TransferWithCurrency(from: Either[(AccountId, Account), Player],
                                  to: Either[(AccountId, Account), Player],
                                  creator: Either[(MemberId, Member), Player],
                                  transaction: Transaction,
                                  currency: Option[Either[String, Currency]])
    extends Transfer

  trait JoinStateListener {
    def onJoinStateChanged(joinState: JoinState)
  }

  trait GameActionListener {
    def onChangeGameNameError(name: Option[String])

    def onChangeIdentityNameError(name: Option[String])

    def onCreateIdentityAccountError(name: Option[String])

    def onCreateIdentityMemberError(name: Option[String])

    def onCreateGameError(name: Option[String])

    def onDeleteIdentityError(name: Option[String])

    def onGameNameChanged(name: Option[String])

    def onIdentitiesUpdated(identities: Map[MemberId, IdentityWithBalance])

    def onIdentityCreated(identity: IdentityWithBalance)

    def onIdentityReceived(identity: IdentityWithBalance)

    def onIdentityRequired()

    def onIdentityRestored(identity: IdentityWithBalance)

    def onJoinGameError()

    def onPlayerAdded(addedPlayer: PlayerWithBalanceAndConnectionState)

    def onPlayerChanged(changedPlayer: PlayerWithBalanceAndConnectionState)

    def onPlayersInitialized(players: Iterable[PlayerWithBalanceAndConnectionState])

    def onPlayerRemoved(removedPlayer: PlayerWithBalanceAndConnectionState)

    def onPlayersUpdated(players: Map[MemberId, PlayerWithBalanceAndConnectionState])

    def onQuitGameError()

    def onRestoreIdentityError(name: Option[String])

    def onTransferAdded(addedTransfer: TransferWithCurrency)

    def onTransferIdentityError(name: Option[String])

    def onTransferToPlayerError(name: Option[String])

    def onTransfersChanged(changedTransfers: Iterable[TransferWithCurrency])

    def onTransfersInitialized(transfers: Iterable[TransferWithCurrency])

    def onTransfersUpdated(transfers: Map[TransactionId, TransferWithCurrency])
  }

  class JoinRequestToken

  private class State(var zone: Zone,
                      var connectedClients: Set[PublicKey],
                      var balances: Map[AccountId, BigDecimal],
                      var currency: Option[Either[String, Currency]],
                      var memberIdsToAccountIds: Map[MemberId, AccountId],
                      var accountIdsToMemberIds: Map[AccountId, MemberId],
                      var identities: Map[MemberId, IdentityWithBalance],
                      var hiddenIdentities: Map[MemberId, IdentityWithBalance],
                      var players: Map[MemberId, PlayerWithBalanceAndConnectionState],
                      var hiddenPlayers: Map[MemberId, PlayerWithBalanceAndConnectionState],
                      var transfers: Map[TransactionId, TransferWithCurrency])

  private final val CurrencyCodeKey = "currency"
  private final val HiddenFlagKey = "hidden"

  private var instances = Map.empty[ZoneId, BoardGame]

  def getInstance(zoneId: ZoneId): BoardGame = instances.get(zoneId).orNull

  def isGameNameValid(name: CharSequence): Boolean = isTagValid(name)

  def isTagValid(tag: CharSequence): Boolean = tag.length > 0 && tag.length <= MaxStringLength

  private def currencyFromMetadata(metadata: Option[JsObject]): Option[Either[String, Currency]] =
    metadata.flatMap(
      _.value.get(CurrencyCodeKey).flatMap(
        _.asOpt[String].map(currencyCode =>
          Try(
            Currency.getInstance(currencyCode)
          ).toOption.fold[Either[String, Currency]](ifEmpty = Left(currencyCode))(Right(_))
        )
      )
    )

  private def membersAccountsFromAccounts(accounts: Map[AccountId, Account]): Map[MemberId, AccountId] =
    accounts.filter { case (_, account) =>
      account.ownerMemberIds.size == 1
    }.groupBy { case (_, account) =>
      account.ownerMemberIds.head
    }.collect { case (memberId, memberAccounts) if memberAccounts.size == 1 =>
      val (accountId, _) = memberAccounts.head
      memberId -> accountId
    }

  private def identitiesFromMembersAccounts(zoneId: ZoneId,
                                            membersAccounts: Map[MemberId, AccountId],
                                            accounts: Map[AccountId, Account],
                                            balances: Map[AccountId, BigDecimal],
                                            currency: Option[Either[String, Currency]],
                                            members: Map[MemberId, Member],
                                            equityAccountId: AccountId,
                                            clientPublicKey: PublicKey):
  (Map[MemberId, IdentityWithBalance], Map[MemberId, IdentityWithBalance]) =
    membersAccounts.collect {
      case (memberId, accountId) if members(memberId).ownerPublicKey == clientPublicKey =>
        memberId -> IdentityWithBalance(
          zoneId,
          members(memberId),
          accounts(accountId),
          (balances(accountId).bigDecimal, currency),
          accountId == equityAccountId
        )
    }.partition { case (_, identity) =>
      !isHidden(identity.member)
    }

  private def isHidden(member: Member): Boolean =
    member.metadata.fold(ifEmpty = false)(
      _.value.get(HiddenFlagKey).fold(ifEmpty = false)(
        _.asOpt[Boolean].getOrElse(false)
      )
    )

  private def playersFromMembersAccounts(zoneId: ZoneId,
                                         membersAccounts: Map[MemberId, AccountId],
                                         accounts: Map[AccountId, Account],
                                         balances: Map[AccountId, BigDecimal],
                                         currency: Option[Either[String, Currency]],
                                         members: Map[MemberId, Member],
                                         equityAccountId: AccountId,
                                         connectedPublicKeys: Set[PublicKey]):
  (Map[MemberId, PlayerWithBalanceAndConnectionState], Map[MemberId, PlayerWithBalanceAndConnectionState]) =
    membersAccounts.map {
      case (memberId, accountId) =>
        val member = members(memberId)
        memberId -> PlayerWithBalanceAndConnectionState(
          zoneId,
          member,
          accounts(accountId),
          (balances(accountId).bigDecimal, currency),
          accountId == equityAccountId,
          connectedPublicKeys.contains(member.ownerPublicKey)
        )
    }.partition { case (_, identity) =>
      !isHidden(identity.member)
    }

  private def transfersFromTransactions(transactions: Map[TransactionId, Transaction],
                                        currency: Option[Either[String, Currency]],
                                        accountsMembers: Map[AccountId, MemberId],
                                        players: Map[MemberId, Player],
                                        accounts: Map[AccountId, Account],
                                        members: Map[MemberId, Member]): Map[TransactionId, TransferWithCurrency] =
    transactions.map {
      case (transactionId, transaction) =>
        val from = accountsMembers.get(transaction.from)
          .fold[Either[(AccountId, Account), Player]](
          ifEmpty = Left(transaction.from -> accounts(transaction.from))
        )(memberId => Right(players(memberId)))
        val to = accountsMembers.get(transaction.to)
          .fold[Either[(AccountId, Account), Player]](
          ifEmpty = Left(transaction.to -> accounts(transaction.to))
        )(memberId => Right(players(memberId)))
        val creator = players.get(transaction.creator)
          .fold[Either[(MemberId, Member), Player]](
          ifEmpty = Left(transaction.creator -> members(transaction.creator))
        )(Right(_))
        transactionId -> TransferWithCurrency(
          from,
          to,
          creator,
          transaction,
          currency
        )
    }
}

class BoardGame private(serverConnection: ServerConnection,
                        gameDatabase: GameDatabase,
                        currency: Option[Currency],
                        gameName: Option[String],
                        bankMemberName: Option[String],
                        private[this] var zoneId: Option[ZoneId],
                        private[this] var gameId: Option[Future[Long]])
  extends ServerConnection.ConnectionStateListener
    with ServerConnection.NotificationReceiptListener {

  private[this] val connectionRequestToken = new ConnectionRequestToken

  private[this] var state: State = _
  private[this] var _joinState: JoinState = BoardGame.UNAVAILABLE

  private[this] var joinRequestTokens = Set.empty[JoinRequestToken]
  private[this] var joinStateListeners = Set.empty[JoinStateListener]
  private[this] var gameActionListeners = Set.empty[GameActionListener]

  def this(serverConnection: ServerConnection,
           gameDatabase: GameDatabase,
           currency: Currency,
           gameName: String,
           bankMemberName: String) {
    this(
      serverConnection,
      gameDatabase,
      Some(currency),
      Some(gameName),
      Some(bankMemberName),
      None,
      None
    )
  }

  def this(serverConnection: ServerConnection,
           gameDatabase: GameDatabase,
           zoneId: ZoneId) {
    this(
      serverConnection,
      gameDatabase,
      None,
      None,
      None,
      Some(zoneId),
      None
    )
  }

  def this(serverConnection: ServerConnection,
           gameDatabase: GameDatabase,
           zoneId: ZoneId,
           gameId: Long) {
    this(
      serverConnection,
      gameDatabase,
      None,
      None,
      None,
      Some(zoneId),
      Some(Future.successful(gameId))
    )
  }

  def getCurrency: Option[Either[String, Currency]] = state.currency

  def getGameName: Option[String] = state.zone.name

  def getHiddenIdentities: Iterable[IdentityWithBalance] = state.hiddenIdentities.values

  def getIdentities: Iterable[IdentityWithBalance] = state.identities.values

  def getJoinState: JoinState = _joinState

  def getPlayers: Iterable[PlayerWithBalanceAndConnectionState] = state.players.values

  def getZoneId: ZoneId = zoneId.orNull

  def isIdentityNameValid(name: CharSequence): Boolean = isTagValid(name) && state.zone.members(
    state.accountIdsToMemberIds(state.zone.equityAccountId)
  ).name.fold(ifEmpty = true)(_ != name.toString)

  def isPublicKeyConnectedAndImplicitlyValid(publicKey: PublicKey): Boolean =
    state.connectedClients.contains(publicKey)

  def changeGameName(name: String): Unit =
    serverConnection.sendCommand(
      ChangeZoneNameCommand(
        zoneId.get,
        Some(name)
      ),
      new ResponseCallback {
        override def onErrorReceived(errorResponse: ErrorResponse) =
          gameActionListeners.foreach(_.onChangeGameNameError(Some(name)))
      }
    )

  def createIdentity(name: String): Unit =
    serverConnection.sendCommand(
      CreateMemberCommand(
        zoneId.get,
        serverConnection.clientKey,
        Some(name)
      ),
      new ResponseCallback {
        override def onErrorReceived(errorResponse: ErrorResponse) =
          gameActionListeners.foreach(_.onCreateIdentityMemberError(Some(name)))

        override def onResultReceived(resultResponse: ResultResponse) {
          val createMemberResponse = resultResponse.asInstanceOf[CreateMemberResponse]
          createAccount(createMemberResponse.member)
        }
      }
    )

  def changeIdentityName(identity: Identity, name: String): Unit =
    serverConnection.sendCommand(
      UpdateMemberCommand(
        zoneId.get,
        state.identities(identity.member.id).member.copy(name = Some(name))
      ),
      new ResponseCallback {
        override def onErrorReceived(errorResponse: ErrorResponse) =
          gameActionListeners.foreach(_.onChangeIdentityNameError(Some(name)))
      }
    )

  def transferIdentity(identity: Identity, toPublicKey: PublicKey): Unit =
    serverConnection.sendCommand(
      UpdateMemberCommand(
        zoneId.get,
        state.identities(identity.member.id).member.copy(ownerPublicKey = toPublicKey)
      ),
      new ResponseCallback {
        override def onErrorReceived(errorResponse: ErrorResponse) =
          gameActionListeners.foreach(_.onTransferIdentityError(identity.member.name))
      }
    )

  def deleteIdentity(identity: Identity): Unit = {
    val member = state.identities(identity.member.id).member
    serverConnection.sendCommand(
      UpdateMemberCommand(
        zoneId.get,
        member.copy(
          metadata = Some(
            member.metadata.getOrElse(Json.obj()) ++ Json.obj(HiddenFlagKey -> true)
          )
        )
      ),
      new ResponseCallback {
        override def onErrorReceived(errorResponse: ErrorResponse) =
          gameActionListeners.foreach(_.onDeleteIdentityError(member.name))
      }
    )
  }

  def restoreIdentity(identity: Identity): Unit = {
    val member = state.hiddenIdentities(identity.member.id).member
    serverConnection.sendCommand(
      UpdateMemberCommand(
        zoneId.get,
        member.copy(
          metadata = member.metadata.map(_ - HiddenFlagKey)
        )
      ),
      new ResponseCallback {
        override def onErrorReceived(errorResponse: ErrorResponse) =
          gameActionListeners.foreach(_.onRestoreIdentityError(member.name))
      }
    )
  }

  def transferToPlayer(actingAs: Identity, from: Identity, to: Seq[Player], value: BigDecimal): Unit =
    to.foreach(to =>
      serverConnection.sendCommand(
        AddTransactionCommand(
          zoneId.get,
          actingAs.member.id,
          from.account.id,
          to.account.id,
          value
        ),
        new ResponseCallback {
          override def onErrorReceived(errorResponse: ErrorResponse) =
            gameActionListeners.foreach(_.onTransferToPlayerError(to.member.name))
        }
      )
    )

  def registerListener(listener: JoinStateListener): Unit =
    if (!joinStateListeners.contains(listener)) {
      if (joinStateListeners.isEmpty && gameActionListeners.isEmpty && joinRequestTokens.isEmpty) {
        serverConnection.registerListener(this: ConnectionStateListener)
        serverConnection.registerListener(this: NotificationReceiptListener)
      }
      joinStateListeners = joinStateListeners + listener
      listener.onJoinStateChanged(_joinState)
    }

  def registerListener(listener: GameActionListener): Unit =
    if (!gameActionListeners.contains(listener)) {
      if (joinStateListeners.isEmpty && gameActionListeners.isEmpty && joinRequestTokens.isEmpty) {
        serverConnection.registerListener(this: ConnectionStateListener)
        serverConnection.registerListener(this: NotificationReceiptListener)
      }
      gameActionListeners = gameActionListeners + listener
      if (_joinState == BoardGame.JOINED) {
        listener.onGameNameChanged(state.zone.name)
        listener.onIdentitiesUpdated(state.identities)
        listener.onPlayersInitialized(state.players.values)
        listener.onPlayersUpdated(state.players)
        listener.onTransfersInitialized(state.transfers.values)
        listener.onTransfersUpdated(state.transfers)
      }
    }

  def requestJoin(token: JoinRequestToken, retry: Boolean): Unit = {
    zoneId.foreach(zoneId =>
      if (!instances.contains(zoneId)) {
        instances = instances + (zoneId -> BoardGame.this)
      }
    )
    if (!joinRequestTokens.contains(token)) {
      if (joinStateListeners.isEmpty && gameActionListeners.isEmpty && joinRequestTokens.isEmpty) {
        serverConnection.registerListener(this: ConnectionStateListener)
        serverConnection.registerListener(this: NotificationReceiptListener)
      }
      joinRequestTokens = joinRequestTokens + token
    }
    serverConnection.requestConnection(connectionRequestToken, retry)
    if (_joinState != BoardGame.CREATING
      && _joinState != BoardGame.JOINING
      && _joinState != BoardGame.JOINED
      && serverConnection.connectionState == ServerConnection.ONLINE) {
      zoneId.fold(ifEmpty = {
        state = null
        _joinState = BoardGame.CREATING
        joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
        createAndThenJoinZone(currency.get, gameName.get)
      }) { zoneId =>
        state = null
        _joinState = BoardGame.JOINING
        joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
        join(zoneId)
      }
    }
  }

  def unrequestJoin(token: JoinRequestToken): Unit =
    if (joinRequestTokens.contains(token)) {
      joinRequestTokens = joinRequestTokens - token
      if (joinStateListeners.isEmpty && gameActionListeners.isEmpty && joinRequestTokens.isEmpty) {
        serverConnection.unregisterListener(this: NotificationReceiptListener)
        serverConnection.unregisterListener(this: ConnectionStateListener)
      }
      if (joinRequestTokens.isEmpty) {
        zoneId.foreach(zoneId =>
          if (instances.contains(zoneId)) {
            instances = instances - zoneId
          }
        )
        if (_joinState != BoardGame.JOINING && _joinState != BoardGame.JOINED) {
          serverConnection.unrequestConnection(connectionRequestToken)
        } else {
          state = null
          _joinState = BoardGame.QUITTING
          joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
          serverConnection.sendCommand(
            QuitZoneCommand(
              zoneId.get
            ),
            new ResponseCallback {
              override def onErrorReceived(errorResponse: ErrorResponse) =
                gameActionListeners.foreach(_.onQuitGameError())

              override def onResultReceived(resultResponse: ResultResponse) =
                if (joinRequestTokens.nonEmpty) {
                  state = null
                  _joinState = BoardGame.JOINING
                  joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
                  join(zoneId.get)
                } else {
                  serverConnection.unrequestConnection(connectionRequestToken)
                }
            }
          )
        }
      }
    }

  def unregisterListener(listener: GameActionListener): Unit =
    if (gameActionListeners.contains(listener)) {
      gameActionListeners = gameActionListeners - listener
      if (joinStateListeners.isEmpty && gameActionListeners.isEmpty && joinRequestTokens.isEmpty) {
        serverConnection.unregisterListener(this: NotificationReceiptListener)
        serverConnection.unregisterListener(this: ConnectionStateListener)
      }
    }

  def unregisterListener(listener: JoinStateListener): Unit =
    if (joinStateListeners.contains(listener)) {
      joinStateListeners = joinStateListeners - listener
      if (joinStateListeners.isEmpty && gameActionListeners.isEmpty && joinRequestTokens.isEmpty) {
        serverConnection.unregisterListener(this: NotificationReceiptListener)
        serverConnection.unregisterListener(this: ConnectionStateListener)
      }
    }

  override def onConnectionStateChanged(connectionState: ConnectionState): Unit = connectionState match {
    case ServerConnection.UNAVAILABLE =>
      state = null
      _joinState = BoardGame.UNAVAILABLE
      joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
    case ServerConnection.GENERAL_FAILURE =>
      state = null
      _joinState = BoardGame.GENERAL_FAILURE
      joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
    case ServerConnection.TLS_ERROR =>
      state = null
      _joinState = BoardGame.TLS_ERROR
      joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
    case ServerConnection.UNSUPPORTED_VERSION =>
      state = null
      _joinState = BoardGame.UNSUPPORTED_VERSION
      joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
    case ServerConnection.AVAILABLE =>
      state = null
      _joinState = BoardGame.AVAILABLE
      joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
    case ServerConnection.CONNECTING =>
      state = null
      _joinState = BoardGame.CONNECTING
      joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
    case ServerConnection.WAITING_FOR_VERSION_CHECK =>
      state = null
      _joinState = BoardGame.WAITING_FOR_VERSION_CHECK
      joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
    case ServerConnection.ONLINE =>
      if (joinRequestTokens.nonEmpty) {
        zoneId.fold(ifEmpty = {
          state = null
          _joinState = BoardGame.CREATING
          joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
          createAndThenJoinZone(currency.get, gameName.get)
        }) { zoneId =>
          state = null
          _joinState = BoardGame.JOINING
          joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
          join(zoneId)
        }
      }
    case ServerConnection.DISCONNECTING =>
      state = null
      _joinState = BoardGame.DISCONNECTING
      joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
  }

  override def onZoneNotificationReceived(zoneNotification: ZoneNotification): Unit =
    if (_joinState == BoardGame.JOINED && zoneId.get == zoneNotification.zoneId) {
      def updatePlayersAndTransactions(): Unit = {
        val (updatedPlayers, updatedHiddenPlayers) = playersFromMembersAccounts(
          zoneNotification.zoneId,
          state.memberIdsToAccountIds,
          state.zone.accounts,
          state.balances,
          state.currency,
          state.zone.members,
          state.zone.equityAccountId,
          state.connectedClients
        )
        if (updatedPlayers != state.players) {
          val addedPlayers = updatedPlayers -- state.players.keys
          val changedPlayers = updatedPlayers.filter { case (memberId, player) =>
            state.players.get(memberId).fold(ifEmpty = false)(_ != player)
          }
          val removedPlayers = state.players -- updatedPlayers.keys
          if (addedPlayers.nonEmpty) {
            gameActionListeners.foreach(listener =>
              addedPlayers.values.foreach(listener.onPlayerAdded)
            )
          }
          if (changedPlayers.nonEmpty) {
            gameActionListeners.foreach(listener =>
              changedPlayers.values.foreach(listener.onPlayerChanged)
            )
          }
          if (removedPlayers.nonEmpty) {
            gameActionListeners.foreach(listener =>
              removedPlayers.values.foreach(listener.onPlayerRemoved)
            )
          }
          state.players = updatedPlayers
          gameActionListeners.foreach(_.onPlayersUpdated(updatedPlayers))
        }
        if (updatedHiddenPlayers != state.hiddenPlayers) {
          state.hiddenPlayers = updatedHiddenPlayers
        }
        val updatedTransfers = transfersFromTransactions(
          state.zone.transactions,
          state.currency,
          state.accountIdsToMemberIds,
          state.players ++ state.hiddenPlayers,
          state.zone.accounts,
          state.zone.members
        )
        if (updatedTransfers != state.transfers) {
          val changedTransfers = updatedTransfers.filter { case (transactionId, transfer) =>
            state.transfers.get(transactionId).fold(ifEmpty = false)(_ != transfer)
          }
          if (changedTransfers.nonEmpty) {
            gameActionListeners.foreach(_.onTransfersChanged(changedTransfers.values))
          }
          state.transfers = updatedTransfers
          gameActionListeners.foreach(_.onTransfersUpdated(updatedTransfers))
        }
      }
      zoneNotification match {
        case ClientJoinedZoneNotification(_, publicKey) =>
          state.connectedClients = state.connectedClients + publicKey
          val (joinedPlayers, joinedHiddenPlayers) = playersFromMembersAccounts(
            zoneNotification.zoneId,
            state.memberIdsToAccountIds.filterKeys(
              state.zone.members(_).ownerPublicKey == publicKey
            ),
            state.zone.accounts,
            state.balances,
            state.currency,
            state.zone.members,
            state.zone.equityAccountId,
            Set(publicKey)
          )
          if (joinedPlayers.nonEmpty) {
            state.players = state.players ++ joinedPlayers
            gameActionListeners.foreach(listener =>
              joinedPlayers.values.foreach(listener.onPlayerChanged)
            )
            gameActionListeners.foreach(_.onPlayersUpdated(state.players))
          }
          if (joinedHiddenPlayers.nonEmpty) {
            state.hiddenPlayers = state.hiddenPlayers ++ joinedHiddenPlayers
          }
        case ClientQuitZoneNotification(_, publicKey) =>
          state.connectedClients = state.connectedClients - publicKey
          val (quitPlayers, quitHiddenPlayers) = playersFromMembersAccounts(
            zoneNotification.zoneId,
            state.memberIdsToAccountIds.filterKeys(
              state.zone.members(_).ownerPublicKey == publicKey
            ),
            state.zone.accounts,
            state.balances,
            state.currency,
            state.zone.members,
            state.zone.equityAccountId,
            Set.empty
          )
          if (quitPlayers.nonEmpty) {
            state.players = state.players ++ quitPlayers
            gameActionListeners.foreach(listener =>
              quitPlayers.values.foreach(listener.onPlayerChanged)
            )
            gameActionListeners.foreach(_.onPlayersUpdated(state.players))
          }
          if (quitHiddenPlayers.nonEmpty) {
            state.hiddenPlayers = state.hiddenPlayers ++ quitHiddenPlayers
          }
        case ZoneTerminatedNotification(_) =>
          state = null
          _joinState = BoardGame.JOINING
          joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
          join(zoneNotification.zoneId)
        case ZoneNameChangedNotification(_, name) =>
          state.zone = state.zone.copy(name = name)
          gameActionListeners.foreach(_.onGameNameChanged(name))
          gameId.foreach(
            _.foreach { gameId =>
              Future(
                gameDatabase.updateGameName(gameId, name.orNull)
              )
            }
          )
        case MemberCreatedNotification(_, member) =>
          state.zone = state.zone.copy(
            members = state.zone.members + (member.id -> member)
          )
        case MemberUpdatedNotification(_, member) =>
          state.zone = state.zone.copy(
            members = state.zone.members + (member.id -> member)
          )
          val (updatedIdentities, updatedHiddenIdentities) = identitiesFromMembersAccounts(
            zoneNotification.zoneId,
            state.memberIdsToAccountIds,
            state.zone.accounts,
            state.balances,
            state.currency,
            state.zone.members,
            state.zone.equityAccountId,
            serverConnection.clientKey
          )
          if (updatedIdentities != state.identities) {
            val receivedIdentity =
              if (!state.identities.contains(member.id) &&
                !state.hiddenIdentities.contains(member.id)) {
                updatedIdentities.get(member.id)
              } else {
                None
              }
            val restoredIdentity =
              if (!state.identities.contains(member.id) &&
                state.hiddenIdentities.contains(member.id)) {
                updatedIdentities.get(member.id)
              } else {
                None
              }
            state.identities = updatedIdentities
            gameActionListeners.foreach(_.onIdentitiesUpdated(updatedIdentities))
            receivedIdentity.foreach(receivedIdentity =>
              gameActionListeners.foreach(_.onIdentityReceived(receivedIdentity))
            )
            restoredIdentity.foreach(restoredIdentity =>
              gameActionListeners.foreach(_.onIdentityRestored(restoredIdentity))
            )
          }
          if (updatedHiddenIdentities != state.hiddenIdentities) {
            state.hiddenIdentities = updatedHiddenIdentities
          }
          updatePlayersAndTransactions()
        case AccountCreatedNotification(_, account) =>
          state.zone = state.zone.copy(
            accounts = state.zone.accounts + (account.id -> account)
          )
          val createdMembersAccounts = membersAccountsFromAccounts(
            Map(
              account.id -> state.zone.accounts(account.id)
            )
          )
          state.memberIdsToAccountIds = state.memberIdsToAccountIds ++ createdMembersAccounts
          state.accountIdsToMemberIds = state.accountIdsToMemberIds ++
            createdMembersAccounts.map(_.swap)
          val (createdIdentity, createdHiddenIdentity) = identitiesFromMembersAccounts(
            zoneNotification.zoneId,
            createdMembersAccounts,
            state.zone.accounts,
            state.balances,
            state.currency,
            state.zone.members,
            state.zone.equityAccountId,
            serverConnection.clientKey
          )
          if (createdIdentity.nonEmpty) {
            state.identities = state.identities ++ createdIdentity
            gameActionListeners.foreach(_.onIdentitiesUpdated(state.identities))
            gameActionListeners.foreach(
              _.onIdentityCreated(state.identities(account.ownerMemberIds.head))
            )
          }
          if (createdHiddenIdentity.nonEmpty) {
            state.hiddenIdentities = state.hiddenIdentities ++ createdHiddenIdentity
          }
          val (createdPlayer, createdHiddenPlayer) = playersFromMembersAccounts(
            zoneNotification.zoneId,
            createdMembersAccounts,
            state.zone.accounts,
            state.balances,
            state.currency,
            state.zone.members,
            state.zone.equityAccountId,
            state.connectedClients
          )
          if (createdPlayer.nonEmpty) {
            state.players = state.players ++ createdPlayer
            gameActionListeners.foreach(listener =>
              createdPlayer.values.foreach(listener.onPlayerAdded)
            )
            gameActionListeners.foreach(_.onPlayersUpdated(state.players))
          }
          if (createdHiddenPlayer.nonEmpty) {
            state.hiddenPlayers = state.hiddenPlayers ++ createdHiddenPlayer
          }
        case AccountUpdatedNotification(_, account) =>
          state.zone = state.zone.copy(
            accounts = state.zone.accounts + (account.id -> account)
          )
          state.memberIdsToAccountIds = membersAccountsFromAccounts(state.zone.accounts)
          state.accountIdsToMemberIds = state.memberIdsToAccountIds.map(_.swap)
          val (updatedIdentities, updatedHiddenIdentities) = identitiesFromMembersAccounts(
            zoneNotification.zoneId,
            state.memberIdsToAccountIds,
            state.zone.accounts,
            state.balances,
            state.currency,
            state.zone.members,
            state.zone.equityAccountId,
            serverConnection.clientKey
          )
          if (updatedIdentities != state.identities) {
            state.identities = updatedIdentities
            gameActionListeners.foreach(_.onIdentitiesUpdated(updatedIdentities))
          }
          if (updatedHiddenIdentities != state.hiddenIdentities) {
            state.hiddenIdentities = updatedHiddenIdentities
          }
          updatePlayersAndTransactions()
        case TransactionAddedNotification(_, transaction) =>
          state.zone = state.zone.copy(
            transactions = state.zone.transactions + (transaction.id -> transaction)
          )
          state.balances = state.balances +
            (transaction.from -> (state.balances(transaction.from) - transaction.value)) +
            (transaction.to -> (state.balances(transaction.to) + transaction.value))
          val changedMembersAccounts = membersAccountsFromAccounts(
            Map(
              transaction.from -> state.zone.accounts(transaction.from),
              transaction.to -> state.zone.accounts(transaction.to)
            )
          )
          val (changedIdentities, changedHiddenIdentities) = identitiesFromMembersAccounts(
            zoneNotification.zoneId,
            changedMembersAccounts,
            state.zone.accounts,
            state.balances,
            state.currency,
            state.zone.members,
            state.zone.equityAccountId,
            serverConnection.clientKey
          )
          if (changedIdentities.nonEmpty) {
            state.identities = state.identities ++ changedIdentities
            gameActionListeners.foreach(_.onIdentitiesUpdated(state.identities))
          }
          if (changedHiddenIdentities.nonEmpty) {
            state.hiddenIdentities = state.hiddenIdentities ++ changedHiddenIdentities
          }
          val (changedPlayers, changedHiddenPlayers) = playersFromMembersAccounts(
            zoneNotification.zoneId,
            changedMembersAccounts,
            state.zone.accounts,
            state.balances,
            state.currency,
            state.zone.members,
            state.zone.equityAccountId,
            state.connectedClients
          )
          if (changedPlayers.nonEmpty) {
            state.players = state.players ++ changedPlayers
            gameActionListeners.foreach(listener =>
              changedPlayers.values.foreach(listener.onPlayerChanged)
            )
            gameActionListeners.foreach(_.onPlayersUpdated(state.players))
          }
          if (changedHiddenPlayers.nonEmpty) {
            state.hiddenPlayers = state.hiddenPlayers ++ changedHiddenPlayers
          }
          val createdTransfer = transfersFromTransactions(
            Map(
              transaction.id -> transaction
            ),
            state.currency,
            state.accountIdsToMemberIds,
            state.players ++ state.hiddenPlayers,
            state.zone.accounts,
            state.zone.members
          )
          if (createdTransfer.nonEmpty) {
            state.transfers = state.transfers ++ createdTransfer
            gameActionListeners.foreach(listener =>
              createdTransfer.values.foreach(listener.onTransferAdded)
            )
            gameActionListeners.foreach(_.onTransfersUpdated(state.transfers))
          }
      }
    }

  private[this] def createAndThenJoinZone(currency: Currency, name: String): Unit =
    serverConnection.sendCommand(
      CreateZoneCommand(
        serverConnection.clientKey,
        bankMemberName,
        None,
        None,
        None,
        Some(name),
        Some(
          Json.obj(
            CurrencyCodeKey -> currency.getCurrencyCode
          )
        )
      ),
      new ResponseCallback {
        override def onErrorReceived(errorResponse: ErrorResponse) =
          gameActionListeners.foreach(_.onCreateGameError(Some(name)))

        override def onResultReceived(resultResponse: ResultResponse) =
          if (_joinState == BoardGame.CREATING) {
            val createZoneResponse = resultResponse.asInstanceOf[CreateZoneResponse]
            instances = instances + (createZoneResponse.zone.id -> BoardGame.this)
            zoneId = Some(createZoneResponse.zone.id)
            state = null
            _joinState = BoardGame.JOINING
            joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
            join(createZoneResponse.zone.id)
          }
      }
    )

  private[this] def join(zoneId: ZoneId): Unit =
    serverConnection.sendCommand(
      JoinZoneCommand(
        zoneId
      ),
      new ResponseCallback {
        override def onErrorReceived(errorResponse: ErrorResponse) =
          gameActionListeners.foreach(_.onJoinGameError())

        override def onResultReceived(resultResponse: ResultResponse) =
          if (_joinState == BoardGame.JOINING) {
            val joinZoneResponse = resultResponse.asInstanceOf[JoinZoneResponse]
            var balances = Map.empty[AccountId, BigDecimal].withDefaultValue(BigDecimal(0))
            for (transaction <- joinZoneResponse.zone.transactions.values) {
              balances = balances +
                (transaction.from -> (balances(transaction.from) - transaction.value)) +
                (transaction.to -> (balances(transaction.to) + transaction.value))
            }
            val currency = currencyFromMetadata(joinZoneResponse.zone.metadata)
            val memberIdsToAccountIds = membersAccountsFromAccounts(
              joinZoneResponse.zone.accounts
            )
            val accountIdsToMemberIds = memberIdsToAccountIds.map(_.swap)
            val (identities, hiddenIdentities) = identitiesFromMembersAccounts(
              zoneId,
              memberIdsToAccountIds,
              joinZoneResponse.zone.accounts,
              balances,
              currency,
              joinZoneResponse.zone.members,
              joinZoneResponse.zone.equityAccountId,
              serverConnection.clientKey
            )
            val (players, hiddenPlayers) = playersFromMembersAccounts(
              zoneId,
              memberIdsToAccountIds,
              joinZoneResponse.zone.accounts,
              balances,
              currency,
              joinZoneResponse.zone.members,
              joinZoneResponse.zone.equityAccountId,
              joinZoneResponse.connectedClients
            )
            val transfers = transfersFromTransactions(
              joinZoneResponse.zone.transactions,
              currency,
              accountIdsToMemberIds,
              players ++ hiddenPlayers,
              joinZoneResponse.zone.accounts,
              joinZoneResponse.zone.members
            )
            state = new State(
              joinZoneResponse.zone,
              joinZoneResponse.connectedClients,
              balances,
              currency,
              memberIdsToAccountIds,
              accountIdsToMemberIds,
              identities,
              hiddenIdentities,
              players,
              hiddenPlayers,
              transfers
            )
            _joinState = BoardGame.JOINED
            joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
            gameActionListeners.foreach(_.onGameNameChanged(joinZoneResponse.zone.name))
            gameActionListeners.foreach(_.onIdentitiesUpdated(identities))
            gameActionListeners.foreach(_.onPlayersInitialized(players.values))
            gameActionListeners.foreach(_.onPlayersUpdated(players))
            gameActionListeners.foreach(_.onTransfersInitialized(transfers.values))
            gameActionListeners.foreach(_.onTransfersUpdated(transfers))
            val partiallyCreatedIdentities = joinZoneResponse.zone.members.collect {
              case (memberId, member) if serverConnection.clientKey == member.ownerPublicKey
                && !joinZoneResponse.zone.accounts.values.exists(_.ownerMemberIds == Set(memberId))
              =>
                member
            }
            partiallyCreatedIdentities.foreach(createAccount)

            // Since we must only prompt for a required identity if none exist yet and since having one or more
            // partially created identities implies that gameId would be set, we can proceed here without checking that
            // partiallyCreatedIdentityIds is non empty.
            //
            // The second condition isn't usually of significance but exists to prevent incorrectly prompting for an
            // identity if a user rejoins a game by scanning its code again rather than by clicking its list item.
            if (gameId.isEmpty && !(identities ++ hiddenIdentities).values.exists(
              _.account.id != joinZoneResponse.zone.equityAccountId
            )) {
              gameActionListeners.foreach(_.onIdentityRequired())
            }

            // We don't set gameId until now as it also indicates above whether we've prompted for the required
            // identity - which we must do at most once.
            gameId = gameId.fold(
              ifEmpty = Some(
                Future(

                  // This is in case a user rejoins a game by scanning its code again rather than by clicking its list
                  // item - in such cases we mustn't attempt to insert an entry as that would silently fail (as it
                  // happens on the Future's worker thread), but we may need to update the existing entries name.
                  Option(gameDatabase.checkAndUpdateGame(
                    zoneId,
                    joinZoneResponse.zone.name.orNull
                  )).map(_.toLong).getOrElse {
                    gameDatabase.insertGame(
                      zoneId,
                      joinZoneResponse.zone.created,
                      joinZoneResponse.zone.expires,
                      joinZoneResponse.zone.name.orNull
                    )
                  }
                )
              )
            ) { gameId =>
              gameId.foreach(_ =>
                Future(
                  gameDatabase.checkAndUpdateGame(
                    zoneId,
                    joinZoneResponse.zone.name.orNull
                  )
                )
              )
              Some(gameId)
            }
          }
      }
    )

  private[this] def createAccount(ownerMember: Member): Unit =
    serverConnection.sendCommand(
      CreateAccountCommand(
        zoneId.get,
        Set(ownerMember.id)
      ),
      new ResponseCallback {
        override def onErrorReceived(errorResponse: ErrorResponse) =
          gameActionListeners.foreach(_.onCreateIdentityAccountError(ownerMember.name))
      }
    )
}
