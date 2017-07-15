package com.dhpcs.liquidity.boardgame

import java.util.Currency

import cats.data.Validated.{Invalid, Valid}
import com.dhpcs.liquidity.actor.protocol._
import com.dhpcs.liquidity.boardgame.BoardGame._
import com.dhpcs.liquidity.client.ServerConnection
import com.dhpcs.liquidity.client.ServerConnection._
import com.dhpcs.liquidity.model._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object BoardGame {

  trait GameDatabase {

    def insertGame(zoneId: ZoneId, created: Long, expires: Long, name: String): Long
    def checkAndUpdateGame(zoneId: ZoneId, name: String): java.lang.Long
    def updateGameName(gameId: Long, name: String): Unit

  }

  sealed abstract class JoinState
  case object UNAVAILABLE     extends JoinState
  case object GENERAL_FAILURE extends JoinState
  case object TLS_ERROR       extends JoinState
  case object AVAILABLE       extends JoinState
  case object CONNECTING      extends JoinState
  case object AUTHENTICATING  extends JoinState
  case object CREATING        extends JoinState
  case object JOINING         extends JoinState
  case object JOINED          extends JoinState
  case object QUITTING        extends JoinState
  case object DISCONNECTING   extends JoinState

  sealed abstract class Player extends Serializable {

    def zoneId: ZoneId
    def member: Member
    def account: Account
    def isBanker: Boolean

  }

  sealed abstract class Identity extends Player
  final case class PlayerWithBalanceAndConnectionState(
      zoneId: ZoneId,
      member: Member,
      account: Account,
      balanceWithCurrency: (BigDecimal, Option[Either[String, Currency]]),
      isBanker: Boolean,
      isConnected: Boolean)
      extends Player
  final case class IdentityWithBalance(zoneId: ZoneId,
                                       member: Member,
                                       account: Account,
                                       balanceWithCurrency: (BigDecimal, Option[Either[String, Currency]]),
                                       isBanker: Boolean)
      extends Identity

  sealed abstract class Transfer extends Serializable {

    def creator: Either[(MemberId, Member), Player]
    def from: Either[(AccountId, Account), Player]
    def to: Either[(AccountId, Account), Player]
    def transaction: Transaction

  }
  final case class TransferWithCurrency(from: Either[(AccountId, Account), Player],
                                        to: Either[(AccountId, Account), Player],
                                        creator: Either[(MemberId, Member), Player],
                                        transaction: Transaction,
                                        currency: Option[Either[String, Currency]])
      extends Transfer

  trait JoinStateListener {
    def onJoinStateChanged(joinState: JoinState): Unit
  }

  trait GameActionListener {

    def onChangeGameNameError(name: Option[String]): Unit
    def onChangeIdentityNameError(name: Option[String]): Unit
    def onCreateIdentityAccountError(name: Option[String]): Unit
    def onCreateIdentityMemberError(name: Option[String]): Unit
    def onCreateGameError(name: Option[String]): Unit
    def onDeleteIdentityError(name: Option[String]): Unit
    def onGameNameChanged(name: Option[String]): Unit
    def onIdentitiesUpdated(identities: Map[MemberId, IdentityWithBalance]): Unit
    def onIdentityCreated(identity: IdentityWithBalance): Unit
    def onIdentityReceived(identity: IdentityWithBalance): Unit
    def onIdentityRequired(): Unit
    def onIdentityRestored(identity: IdentityWithBalance): Unit
    def onJoinGameError(): Unit
    def onPlayerAdded(addedPlayer: PlayerWithBalanceAndConnectionState): Unit
    def onPlayerChanged(changedPlayer: PlayerWithBalanceAndConnectionState): Unit
    def onPlayersInitialized(players: Iterable[PlayerWithBalanceAndConnectionState]): Unit
    def onPlayerRemoved(removedPlayer: PlayerWithBalanceAndConnectionState): Unit
    def onPlayersUpdated(players: Map[MemberId, PlayerWithBalanceAndConnectionState]): Unit
    def onQuitGameError(): Unit
    def onRestoreIdentityError(name: Option[String]): Unit
    def onTransferAdded(addedTransfer: TransferWithCurrency): Unit
    def onTransferIdentityError(name: Option[String]): Unit
    def onTransferToPlayerError(name: Option[String]): Unit
    def onTransfersChanged(changedTransfers: Iterable[TransferWithCurrency]): Unit
    def onTransfersInitialized(transfers: Iterable[TransferWithCurrency]): Unit
    def onTransfersUpdated(transfers: Map[TransactionId, TransferWithCurrency]): Unit

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
  private final val HiddenFlagKey   = "hidden"

  private var instances = Map.empty[ZoneId, BoardGame]

  def getInstance(zoneId: ZoneId): BoardGame = instances.get(zoneId).orNull

  def isGameNameValid(name: CharSequence): Boolean = isTagValid(name)

  def isTagValid(tag: CharSequence): Boolean = tag.length > 0 && tag.length <= MaxStringLength

  private def currencyFromMetadata(
      metadata: Option[com.google.protobuf.struct.Struct]): Option[Either[String, Currency]] =
    for {
      metadata     <- metadata
      currencyCode <- metadata.fields.get(CurrencyCodeKey).flatMap(_.kind.stringValue)
    } yield
      try Right(Currency.getInstance(currencyCode))
      catch {
        case _: IllegalArgumentException => Left(currencyCode)
      }

  private def membersAccountsFromAccounts(accounts: Map[AccountId, Account]): Map[MemberId, AccountId] =
    accounts
      .filter {
        case (_, account) =>
          account.ownerMemberIds.size == 1
      }
      .groupBy {
        case (_, account) =>
          account.ownerMemberIds.head
      }
      .collect {
        case (memberId, memberAccounts) if memberAccounts.size == 1 =>
          val (accountId, _) = memberAccounts.head
          memberId -> accountId
      }

  private def identitiesFromMembersAccounts(
      zoneId: ZoneId,
      membersAccounts: Map[MemberId, AccountId],
      accounts: Map[AccountId, Account],
      balances: Map[AccountId, BigDecimal],
      currency: Option[Either[String, Currency]],
      members: Map[MemberId, Member],
      equityAccountId: AccountId,
      clientPublicKey: PublicKey): (Map[MemberId, IdentityWithBalance], Map[MemberId, IdentityWithBalance]) =
    membersAccounts
      .collect {
        case (memberId, accountId) if members(memberId).ownerPublicKey == clientPublicKey =>
          memberId -> IdentityWithBalance(
            zoneId,
            members(memberId),
            accounts(accountId),
            (balances(accountId).bigDecimal, currency),
            accountId == equityAccountId
          )
      }
      .partition {
        case (_, identity) =>
          !isHidden(identity.member)
      }

  private def isHidden(member: Member): Boolean =
    member.metadata.flatMap(_.fields.get(HiddenFlagKey)).flatMap(_.kind.boolValue).getOrElse(false)

  private def playersFromMembersAccounts(zoneId: ZoneId,
                                         membersAccounts: Map[MemberId, AccountId],
                                         accounts: Map[AccountId, Account],
                                         balances: Map[AccountId, BigDecimal],
                                         currency: Option[Either[String, Currency]],
                                         members: Map[MemberId, Member],
                                         equityAccountId: AccountId,
                                         connectedPublicKeys: Set[PublicKey])
    : (Map[MemberId, PlayerWithBalanceAndConnectionState], Map[MemberId, PlayerWithBalanceAndConnectionState]) =
    membersAccounts
      .map {
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
      }
      .partition {
        case (_, identity) =>
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
        val from = accountsMembers.get(transaction.from) match {
          case None           => Left(transaction.from -> accounts(transaction.from))
          case Some(memberId) => Right(players(memberId))
        }
        val to = accountsMembers.get(transaction.to) match {
          case None           => Left(transaction.to -> accounts(transaction.to))
          case Some(memberId) => Right(players(memberId))
        }
        val creator = players.get(transaction.creator) match {
          case None           => Left(transaction.creator -> members(transaction.creator))
          case Some(memberId) => Right(memberId)
        }
        transactionId -> TransferWithCurrency(
          from,
          to,
          creator,
          transaction,
          currency
        )
    }
}

class BoardGame private (serverConnection: ServerConnection,
                         gameDatabase: GameDatabase,
                         currency: Option[Currency],
                         gameName: Option[String],
                         bankMemberName: Option[String],
                         private[this] var zoneId: Option[ZoneId],
                         private[this] var gameId: Option[Future[Long]])
    extends ServerConnection.ConnectionStateListener
    with ServerConnection.NotificationReceiptListener {

  private[this] val connectionRequestToken = new ConnectionRequestToken

  private[this] var state: State          = _
  private[this] var _joinState: JoinState = BoardGame.UNAVAILABLE

  private[this] var joinRequestTokens   = Set.empty[JoinRequestToken]
  private[this] var joinStateListeners  = Set.empty[JoinStateListener]
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

  def this(serverConnection: ServerConnection, gameDatabase: GameDatabase, zoneId: ZoneId) {
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

  def this(serverConnection: ServerConnection, gameDatabase: GameDatabase, zoneId: ZoneId, gameId: Long) {
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

  def isIdentityNameValid(name: CharSequence): Boolean =
    isTagValid(name) &&
      !state.zone.members(state.accountIdsToMemberIds(state.zone.equityAccountId)).name.contains(name.toString)

  def isPublicKeyConnectedAndImplicitlyValid(publicKey: PublicKey): Boolean =
    state.connectedClients.contains(publicKey)

  def changeGameName(name: String): Unit =
    serverConnection.sendZoneCommand(
      zoneId.get,
      ChangeZoneNameCommand(
        Some(name)
      ),
      new ResponseCallback {
        override def onZoneResponse(zoneResponse: ZoneResponse): Unit =
          zoneResponse.asInstanceOf[ChangeZoneNameResponse].result match {
            case Invalid(_) =>
              gameActionListeners.foreach(_.onChangeGameNameError(Some(name)))
            case Valid(_) => ()
          }
      }
    )

  def createIdentity(name: String): Unit =
    serverConnection.sendZoneCommand(
      zoneId.get,
      CreateMemberCommand(
        serverConnection.clientKey,
        Some(name)
      ),
      new ResponseCallback {
        override def onZoneResponse(zoneResponse: ZoneResponse): Unit =
          zoneResponse.asInstanceOf[CreateMemberResponse].result match {
            case Invalid(_) =>
              gameActionListeners.foreach(_.onCreateIdentityMemberError(Some(name)))
            case Valid(member) =>
              createAccount(member)
          }
      }
    )

  def changeIdentityName(identity: Identity, name: String): Unit =
    serverConnection.sendZoneCommand(
      zoneId.get,
      UpdateMemberCommand(
        state.identities(identity.member.id).member.copy(name = Some(name))
      ),
      new ResponseCallback {
        override def onZoneResponse(zoneResponse: ZoneResponse): Unit =
          zoneResponse.asInstanceOf[UpdateMemberResponse].result match {
            case Invalid(_) =>
              gameActionListeners.foreach(_.onChangeIdentityNameError(Some(name)))
            case Valid(_) => ()
          }
      }
    )

  def transferIdentity(identity: Identity, toPublicKey: PublicKey): Unit =
    serverConnection.sendZoneCommand(
      zoneId.get,
      UpdateMemberCommand(
        state.identities(identity.member.id).member.copy(ownerPublicKey = toPublicKey)
      ),
      new ResponseCallback {
        override def onZoneResponse(zoneResponse: ZoneResponse): Unit =
          zoneResponse.asInstanceOf[UpdateMemberResponse].result match {
            case Invalid(_) =>
              gameActionListeners.foreach(_.onTransferIdentityError(identity.member.name))
            case Valid(_) => ()
          }
      }
    )

  def deleteIdentity(identity: Identity): Unit = {
    val member = state.identities(identity.member.id).member
    serverConnection.sendZoneCommand(
      zoneId.get,
      UpdateMemberCommand(
        member.copy(
          metadata = Some(
            member.metadata
              .getOrElse(com.google.protobuf.struct.Struct.defaultInstance)
              .addFields(HiddenFlagKey -> com.google.protobuf.struct
                .Value(com.google.protobuf.struct.Value.Kind.BoolValue(true)))
          )
        )
      ),
      new ResponseCallback {
        override def onZoneResponse(zoneResponse: ZoneResponse): Unit =
          zoneResponse.asInstanceOf[UpdateMemberResponse].result match {
            case Invalid(_) =>
              gameActionListeners.foreach(_.onDeleteIdentityError(member.name))
            case Valid(_) => ()
          }
      }
    )
  }

  def restoreIdentity(identity: Identity): Unit = {
    val member = state.hiddenIdentities(identity.member.id).member
    serverConnection.sendZoneCommand(
      zoneId.get,
      UpdateMemberCommand(
        member.copy(
          metadata = member.metadata.map(
            metadata =>
              com.google.protobuf.struct.Struct(
                metadata.fields - HiddenFlagKey
            ))
        )
      ),
      new ResponseCallback {
        override def onZoneResponse(zoneResponse: ZoneResponse): Unit =
          zoneResponse.asInstanceOf[UpdateMemberResponse].result match {
            case Invalid(_) =>
              gameActionListeners.foreach(_.onRestoreIdentityError(member.name))
            case Valid(_) => ()
          }
      }
    )
  }

  def transferToPlayer(actingAs: Identity, from: Identity, to: Seq[Player], value: BigDecimal): Unit =
    to.foreach(
      to =>
        serverConnection.sendZoneCommand(
          zoneId.get,
          AddTransactionCommand(
            actingAs.member.id,
            from.account.id,
            to.account.id,
            value
          ),
          new ResponseCallback {
            override def onZoneResponse(zoneResponse: ZoneResponse): Unit =
              zoneResponse.asInstanceOf[AddTransactionResponse].result match {
                case Invalid(_) =>
                  gameActionListeners.foreach(_.onTransferToPlayerError(to.member.name))
                case Valid(_) => ()
              }
          }
      ))

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
    zoneId.foreach(
      zoneId =>
        if (!instances.contains(zoneId))
          instances = instances + (zoneId -> BoardGame.this))
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
        && serverConnection.connectionState == ServerConnection.ONLINE)
      zoneId match {
        case None =>
          state = null
          _joinState = BoardGame.CREATING
          joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
          createAndThenJoinZone(currency.get, gameName.get)
        case Some(_) =>
          state = null
          _joinState = BoardGame.JOINING
          joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
          zoneId.foreach(join)
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
        zoneId.foreach(
          zoneId =>
            if (instances.contains(zoneId))
              instances = instances - zoneId)
        if (_joinState != BoardGame.JOINING && _joinState != BoardGame.JOINED)
          serverConnection.unrequestConnection(connectionRequestToken)
        else {
          state = null
          _joinState = BoardGame.QUITTING
          joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
          serverConnection.sendZoneCommand(
            zoneId.get,
            QuitZoneCommand,
            new ResponseCallback {
              override def onZoneResponse(zoneResponse: ZoneResponse): Unit =
                zoneResponse.asInstanceOf[QuitZoneResponse].result match {
                  case Invalid(_) =>
                    gameActionListeners.foreach(_.onQuitGameError())
                  case Valid(_) =>
                    ()
                    if (joinRequestTokens.nonEmpty) {
                      state = null
                      _joinState = BoardGame.JOINING
                      joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
                      join(zoneId.get)
                    } else serverConnection.unrequestConnection(connectionRequestToken)
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
    case ServerConnection.AVAILABLE =>
      state = null
      _joinState = BoardGame.AVAILABLE
      joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
    case ServerConnection.AUTHENTICATING =>
      state = null
      _joinState = BoardGame.AUTHENTICATING
      joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
    case ServerConnection.CONNECTING =>
      state = null
      _joinState = BoardGame.CONNECTING
      joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
    case ServerConnection.ONLINE =>
      if (joinRequestTokens.nonEmpty)
        zoneId match {
          case None =>
            state = null
            _joinState = BoardGame.CREATING
            joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
            createAndThenJoinZone(currency.get, gameName.get)
          case Some(_) =>
            state = null
            _joinState = BoardGame.JOINING
            joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
            zoneId.foreach(join)
        }
    case ServerConnection.DISCONNECTING =>
      state = null
      _joinState = BoardGame.DISCONNECTING
      joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
  }

  override def onZoneNotificationReceived(notificationZoneId: ZoneId, zoneNotification: ZoneNotification): Unit =
    if (_joinState == BoardGame.JOINED && zoneId.get == notificationZoneId) {
      def updatePlayersAndTransactions(): Unit = {
        val (updatedPlayers, updatedHiddenPlayers) = playersFromMembersAccounts(
          notificationZoneId,
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
          val changedPlayers = updatedPlayers.filter {
            case (memberId, player) =>
              state.players.get(memberId).exists(_ != player)
          }
          val removedPlayers = state.players -- updatedPlayers.keys
          if (addedPlayers.nonEmpty)
            gameActionListeners.foreach(listener => addedPlayers.values.foreach(listener.onPlayerAdded))
          if (changedPlayers.nonEmpty)
            gameActionListeners.foreach(listener => changedPlayers.values.foreach(listener.onPlayerChanged))
          if (removedPlayers.nonEmpty)
            gameActionListeners.foreach(listener => removedPlayers.values.foreach(listener.onPlayerRemoved))
          state.players = updatedPlayers
          gameActionListeners.foreach(_.onPlayersUpdated(updatedPlayers))
        }
        if (updatedHiddenPlayers != state.hiddenPlayers)
          state.hiddenPlayers = updatedHiddenPlayers
        val updatedTransfers = transfersFromTransactions(
          state.zone.transactions,
          state.currency,
          state.accountIdsToMemberIds,
          state.players ++ state.hiddenPlayers,
          state.zone.accounts,
          state.zone.members
        )
        if (updatedTransfers != state.transfers) {
          val changedTransfers = updatedTransfers.filter {
            case (transactionId, transfer) =>
              state.transfers.get(transactionId).exists(_ != transfer)
          }
          if (changedTransfers.nonEmpty)
            gameActionListeners.foreach(_.onTransfersChanged(changedTransfers.values))
          state.transfers = updatedTransfers
          gameActionListeners.foreach(_.onTransfersUpdated(updatedTransfers))
        }
      }
      zoneNotification match {
        case EmptyZoneNotification =>
        case ClientJoinedZoneNotification(publicKey) =>
          state.connectedClients = state.connectedClients + publicKey
          val (joinedPlayers, joinedHiddenPlayers) = playersFromMembersAccounts(
            notificationZoneId,
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
            gameActionListeners.foreach(listener => joinedPlayers.values.foreach(listener.onPlayerChanged))
            gameActionListeners.foreach(_.onPlayersUpdated(state.players))
          }
          if (joinedHiddenPlayers.nonEmpty)
            state.hiddenPlayers = state.hiddenPlayers ++ joinedHiddenPlayers
        case ClientQuitZoneNotification(publicKey) =>
          state.connectedClients = state.connectedClients - publicKey
          val (quitPlayers, quitHiddenPlayers) = playersFromMembersAccounts(
            notificationZoneId,
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
            gameActionListeners.foreach(listener => quitPlayers.values.foreach(listener.onPlayerChanged))
            gameActionListeners.foreach(_.onPlayersUpdated(state.players))
          }
          if (quitHiddenPlayers.nonEmpty)
            state.hiddenPlayers = state.hiddenPlayers ++ quitHiddenPlayers
        case ZoneTerminatedNotification =>
          state = null
          _joinState = BoardGame.JOINING
          joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
          join(notificationZoneId)
        case ZoneNameChangedNotification(name) =>
          state.zone = state.zone.copy(name = name)
          gameActionListeners.foreach(_.onGameNameChanged(name))
          gameId.foreach(
            _.foreach { gameId =>
              Future(
                gameDatabase.updateGameName(gameId, name.orNull)
              )
            }
          )
        case MemberCreatedNotification(member) =>
          state.zone = state.zone.copy(
            members = state.zone.members + (member.id -> member)
          )
        case MemberUpdatedNotification(member) =>
          state.zone = state.zone.copy(
            members = state.zone.members + (member.id -> member)
          )
          val (updatedIdentities, updatedHiddenIdentities) = identitiesFromMembersAccounts(
            notificationZoneId,
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
                  !state.hiddenIdentities.contains(member.id))
                updatedIdentities.get(member.id)
              else None
            val restoredIdentity =
              if (!state.identities.contains(member.id) &&
                  state.hiddenIdentities.contains(member.id))
                updatedIdentities.get(member.id)
              else None
            state.identities = updatedIdentities
            gameActionListeners.foreach(_.onIdentitiesUpdated(updatedIdentities))
            receivedIdentity.foreach(receivedIdentity =>
              gameActionListeners.foreach(_.onIdentityReceived(receivedIdentity)))
            restoredIdentity.foreach(restoredIdentity =>
              gameActionListeners.foreach(_.onIdentityRestored(restoredIdentity)))
          }
          if (updatedHiddenIdentities != state.hiddenIdentities)
            state.hiddenIdentities = updatedHiddenIdentities
          updatePlayersAndTransactions()
        case AccountCreatedNotification(account) =>
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
            notificationZoneId,
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
          if (createdHiddenIdentity.nonEmpty)
            state.hiddenIdentities = state.hiddenIdentities ++ createdHiddenIdentity
          val (createdPlayer, createdHiddenPlayer) = playersFromMembersAccounts(
            notificationZoneId,
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
            gameActionListeners.foreach(listener => createdPlayer.values.foreach(listener.onPlayerAdded))
            gameActionListeners.foreach(_.onPlayersUpdated(state.players))
          }
          if (createdHiddenPlayer.nonEmpty)
            state.hiddenPlayers = state.hiddenPlayers ++ createdHiddenPlayer
        case AccountUpdatedNotification(account) =>
          state.zone = state.zone.copy(
            accounts = state.zone.accounts + (account.id -> account)
          )
          state.memberIdsToAccountIds = membersAccountsFromAccounts(state.zone.accounts)
          state.accountIdsToMemberIds = state.memberIdsToAccountIds.map(_.swap)
          val (updatedIdentities, updatedHiddenIdentities) = identitiesFromMembersAccounts(
            notificationZoneId,
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
          if (updatedHiddenIdentities != state.hiddenIdentities)
            state.hiddenIdentities = updatedHiddenIdentities
          updatePlayersAndTransactions()
        case TransactionAddedNotification(transaction) =>
          state.zone = state.zone.copy(
            transactions = state.zone.transactions + (transaction.id -> transaction)
          )
          state.balances = state.balances +
            (transaction.from -> (state.balances(transaction.from) - transaction.value)) +
            (transaction.to   -> (state.balances(transaction.to) + transaction.value))
          val changedMembersAccounts = membersAccountsFromAccounts(
            Map(
              transaction.from -> state.zone.accounts(transaction.from),
              transaction.to   -> state.zone.accounts(transaction.to)
            )
          )
          val (changedIdentities, changedHiddenIdentities) = identitiesFromMembersAccounts(
            notificationZoneId,
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
          if (changedHiddenIdentities.nonEmpty)
            state.hiddenIdentities = state.hiddenIdentities ++ changedHiddenIdentities
          val (changedPlayers, changedHiddenPlayers) = playersFromMembersAccounts(
            notificationZoneId,
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
            gameActionListeners.foreach(listener => changedPlayers.values.foreach(listener.onPlayerChanged))
            gameActionListeners.foreach(_.onPlayersUpdated(state.players))
          }
          if (changedHiddenPlayers.nonEmpty)
            state.hiddenPlayers = state.hiddenPlayers ++ changedHiddenPlayers
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
            gameActionListeners.foreach(listener => createdTransfer.values.foreach(listener.onTransferAdded))
            gameActionListeners.foreach(_.onTransfersUpdated(state.transfers))
          }
      }
    }

  private[this] def createAndThenJoinZone(currency: Currency, name: String): Unit =
    serverConnection.sendCreateZoneCommand(
      CreateZoneCommand(
        serverConnection.clientKey,
        bankMemberName,
        None,
        None,
        None,
        Some(name),
        Some(
          com.google.protobuf.struct.Struct(
            Map(
              CurrencyCodeKey -> com.google.protobuf.struct
                .Value(com.google.protobuf.struct.Value.Kind.StringValue(currency.getCurrencyCode))
            )))
      ),
      new ResponseCallback {
        override def onZoneResponse(zoneResponse: ZoneResponse): Unit =
          if (_joinState == BoardGame.CREATING) {
            zoneResponse.asInstanceOf[CreateZoneResponse].result match {
              case Invalid(_) =>
                gameActionListeners.foreach(_.onCreateGameError(Some(name)))
              case Valid(zone) =>
                instances = instances + (zone.id -> BoardGame.this)
                zoneId = Some(zone.id)
                state = null
                _joinState = BoardGame.JOINING
                joinStateListeners.foreach(_.onJoinStateChanged(_joinState))
                join(zone.id)
            }
          }
      }
    )

  private[this] def join(zoneId: ZoneId): Unit =
    serverConnection.sendZoneCommand(
      zoneId,
      JoinZoneCommand,
      new ResponseCallback {
        override def onZoneResponse(zoneResponse: ZoneResponse): Unit =
          if (_joinState == BoardGame.JOINING) {
            zoneResponse.asInstanceOf[JoinZoneResponse].result match {
              case Invalid(_) =>
                gameActionListeners.foreach(_.onJoinGameError())
              case Valid((zone, connectedClients)) =>
                var balances = Map.empty[AccountId, BigDecimal].withDefaultValue(BigDecimal(0))
                for (transaction <- zone.transactions.values) {
                  balances = balances +
                    (transaction.from -> (balances(transaction.from) - transaction.value)) +
                    (transaction.to   -> (balances(transaction.to) + transaction.value))
                }
                val currency = currencyFromMetadata(zone.metadata)
                val memberIdsToAccountIds = membersAccountsFromAccounts(
                  zone.accounts
                )
                val accountIdsToMemberIds = memberIdsToAccountIds.map(_.swap)
                val (identities, hiddenIdentities) = identitiesFromMembersAccounts(
                  zoneId,
                  memberIdsToAccountIds,
                  zone.accounts,
                  balances,
                  currency,
                  zone.members,
                  zone.equityAccountId,
                  serverConnection.clientKey
                )
                val (players, hiddenPlayers) = playersFromMembersAccounts(
                  zoneId,
                  memberIdsToAccountIds,
                  zone.accounts,
                  balances,
                  currency,
                  zone.members,
                  zone.equityAccountId,
                  connectedClients
                )
                val transfers = transfersFromTransactions(
                  zone.transactions,
                  currency,
                  accountIdsToMemberIds,
                  players ++ hiddenPlayers,
                  zone.accounts,
                  zone.members
                )
                state = new State(
                  zone,
                  connectedClients,
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
                gameActionListeners.foreach(_.onGameNameChanged(zone.name))
                gameActionListeners.foreach(_.onIdentitiesUpdated(identities))
                gameActionListeners.foreach(_.onPlayersInitialized(players.values))
                gameActionListeners.foreach(_.onPlayersUpdated(players))
                gameActionListeners.foreach(_.onTransfersInitialized(transfers.values))
                gameActionListeners.foreach(_.onTransfersUpdated(transfers))
                val partiallyCreatedIdentities = zone.members.collect {
                  case (memberId, member)
                      if serverConnection.clientKey == member.ownerPublicKey
                        && !zone.accounts.values.exists(_.ownerMemberIds == Set(memberId)) =>
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
                      _.account.id != zone.equityAccountId
                    )) {
                  gameActionListeners.foreach(_.onIdentityRequired())
                }

                // We don't set gameId until now as it also indicates above whether we've prompted for the required
                // identity - which we must do at most once.
                if (gameId.isEmpty)
                  gameId = Some(
                    Future(
                      // This is in case a user rejoins a game by scanning its code again rather than by clicking its list
                      // item - in such cases we mustn't attempt to insert an entry as that would silently fail (as it
                      // happens on the Future's worker thread), but we may need to update the existing entries name.
                      Option(
                        gameDatabase.checkAndUpdateGame(
                          zoneId,
                          zone.name.orNull
                        )).map(_.toLong).getOrElse {
                        gameDatabase.insertGame(
                          zoneId,
                          zone.created,
                          zone.expires,
                          zone.name.orNull
                        )
                      }
                    )
                  )
                else
                  for (gameId <- gameId)
                    gameId.foreach(
                      _ =>
                        Future(
                          gameDatabase.checkAndUpdateGame(
                            zoneId,
                            zone.name.orNull
                          )))
            }
          }
      }
    )

  private[this] def createAccount(ownerMember: Member): Unit =
    serverConnection.sendZoneCommand(
      zoneId.get,
      CreateAccountCommand(
        Set(ownerMember.id)
      ),
      new ResponseCallback {
        override def onZoneResponse(zoneResponse: ZoneResponse): Unit =
          zoneResponse.asInstanceOf[CreateAccountResponse].result match {
            case Invalid(_) =>
              gameActionListeners.foreach(_.onCreateIdentityAccountError(ownerMember.name))
            case Valid(_) => ()
          }
      }
    )

}
