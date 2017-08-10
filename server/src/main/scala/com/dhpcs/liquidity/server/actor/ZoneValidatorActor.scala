package com.dhpcs.liquidity.server.actor

import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.{InvalidKeySpecException, X509EncodedKeySpec}
import java.util.UUID

import akka.actor._
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Publish
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.Passivate
import akka.persistence._
import akka.typed
import akka.typed.Behavior
import akka.typed.scaladsl.adapter._
import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.instances.set._
import cats.syntax.cartesian._
import com.dhpcs.liquidity.actor.protocol._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.persistence._
import com.dhpcs.liquidity.server.actor.ZoneValidatorActor._

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object ZoneValidatorActor {

  def props: Props = Props(new ZoneValidatorActor)

  final val ShardTypeName = "zone-validator"

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case getZoneStateCommand: GetZoneStateCommand => (getZoneStateCommand.zoneId.id.toString, getZoneStateCommand)
    case zoneCommandEnvelope: ZoneCommandEnvelope => (zoneCommandEnvelope.zoneId.id.toString, zoneCommandEnvelope)
  }

  private val NumberOfShards = 10

  val extractShardId: ShardRegion.ExtractShardId = {
    case GetZoneStateCommand(zoneId)                => (math.abs(zoneId.id.hashCode) % NumberOfShards).toString
    case ZoneCommandEnvelope(zoneId, _, _, _, _, _) => (math.abs(zoneId.id.hashCode) % NumberOfShards).toString
  }

  private val SnapShotInterval = 100

  private val ZoneLifetime = 7.days

  private def update(state: ZoneState, event: ZoneEvent): ZoneState = event match {
    case zoneCreatedEvent: ZoneCreatedEvent =>
      state.copy(
        zone = Some(zoneCreatedEvent.zone)
      )
    case ZoneJoinedEvent(_, clientConnectionActorPath, publicKey) =>
      state.copy(
        clientConnections = state.clientConnections + (clientConnectionActorPath -> publicKey)
      )
    case ZoneQuitEvent(_, clientConnectionActorPath) =>
      state.copy(
        clientConnections = state.clientConnections - clientConnectionActorPath
      )
    case ZoneNameChangedEvent(_, name) =>
      state.copy(
        zone = state.zone.map(
          _.copy(
            name = name
          ))
      )
    case MemberCreatedEvent(_, member) =>
      state.copy(
        zone = state.zone.map(
          _.copy(
            members = state.zone.map(_.members).getOrElse(Map.empty) + (member.id -> member)
          ))
      )
    case MemberUpdatedEvent(_, member) =>
      state.copy(
        zone = state.zone.map(
          _.copy(
            members = state.zone.map(_.members).getOrElse(Map.empty) + (member.id -> member)
          ))
      )
    case AccountCreatedEvent(_, account) =>
      state.copy(
        zone = state.zone.map(
          _.copy(
            accounts = state.zone.map(_.accounts).getOrElse(Map.empty) + (account.id -> account)
          ))
      )
    case AccountUpdatedEvent(_, account) =>
      state.copy(
        zone = state.zone.map(
          _.copy(
            accounts = state.zone.map(_.accounts).getOrElse(Map.empty) + (account.id -> account)
          ))
      )
    case TransactionAddedEvent(_, transaction) =>
      val updatedSourceBalance      = state.balances(transaction.from) - transaction.value
      val updatedDestinationBalance = state.balances(transaction.to) + transaction.value
      state.copy(
        balances = state.balances +
          (transaction.from -> updatedSourceBalance) +
          (transaction.to   -> updatedDestinationBalance),
        zone = state.zone.map(
          _.copy(
            transactions = state.zone.map(_.transactions).getOrElse(Map.empty) + (transaction.id -> transaction)
          ))
      )
  }

  private case object PublishStatusTimerKey
  private case object PublishStatus

  private object PassivationCountdownActor {

    sealed abstract class PassivationCountdownMessage
    case object Start                extends PassivationCountdownMessage
    case object Stop                 extends PassivationCountdownMessage
    case object CommandReceivedEvent extends PassivationCountdownMessage
    case object RequestPassivate     extends PassivationCountdownMessage

    private final val PassivationTimeout = 2.minutes

    def behaviour(parent: ActorRef): Behavior[PassivationCountdownMessage] =
      typed.scaladsl.Actor.deferred { context =>
        context.self ! Start
        typed.scaladsl.Actor.immutable[PassivationCountdownMessage] { (_, message) =>
          message match {
            case Start                => context.setReceiveTimeout(PassivationTimeout, RequestPassivate)
            case Stop                 => context.cancelReceiveTimeout()
            case CommandReceivedEvent => ()
            case RequestPassivate     => parent ! RequestPassivate
          }
          typed.scaladsl.Actor.same
        }
      }
  }

  private def validatePublicKeys(publicKeys: Set[PublicKey]): ValidatedNel[ZoneResponse.Error, Set[PublicKey]] = {
    def validatePublicKey(publicKey: PublicKey): ValidatedNel[ZoneResponse.Error, PublicKey] =
      Try(
        KeyFactory
          .getInstance("RSA")
          .generatePublic(new X509EncodedKeySpec(publicKey.value.toByteArray))
          .asInstanceOf[RSAPublicKey]) match {
        case Failure(_: InvalidKeySpecException) =>
          Validated.invalidNel(ZoneResponse.Error.invalidPublicKeyType)
        case Failure(_) =>
          Validated.invalidNel(ZoneResponse.Error.invalidPublicKey)
        case Success(value) if value.getModulus.bitLength() != ZoneCommand.RequiredKeySize =>
          Validated.invalidNel(ZoneResponse.Error.invalidPublicKeyLength)
        case Success(_) =>
          Validated.valid(publicKey)
      }
    if (publicKeys.isEmpty) Validated.invalidNel(ZoneResponse.Error.noPublicKeys)
    else
      publicKeys
        .map(validatePublicKey)
        .foldLeft(Validated.valid[NonEmptyList[ZoneResponse.Error], Set[PublicKey]](Set.empty))(
          (validatedPublicKeys, validatedPublicKey) => validatedPublicKeys.combine(validatedPublicKey.map(Set(_))))
  }

  private def validateMemberIds(zone: Zone, memberIds: Set[MemberId]): ValidatedNel[ZoneResponse.Error, Set[MemberId]] = {
    def validateMemberId(memberId: MemberId): ValidatedNel[ZoneResponse.Error, MemberId] =
      if (!zone.members.contains(memberId))
        Validated.invalidNel(ZoneResponse.Error.memberDoesNotExist(memberId))
      else
        Validated.valid(memberId)
    if (memberIds.isEmpty) Validated.invalidNel(ZoneResponse.Error.noMemberIds)
    else
      memberIds
        .map(validateMemberId)
        .foldLeft(Validated.valid[NonEmptyList[ZoneResponse.Error], Set[MemberId]](Set.empty))(
          (validatedMemberIds, validatedMemberId) => validatedMemberIds.combine(validatedMemberId.map(Set(_))))
  }

  private def validateCanUpdateMember(zone: Zone,
                                      memberId: MemberId,
                                      publicKey: PublicKey): ValidatedNel[ZoneResponse.Error, Unit] =
    zone.members.get(memberId) match {
      case None =>
        Validated.invalidNel(ZoneResponse.Error.memberDoesNotExist)
      case Some(member) if !member.ownerPublicKeys.contains(publicKey) =>
        Validated.invalidNel(ZoneResponse.Error.memberKeyMismatch)
      case _ =>
        Validated.valid(())
    }

  private def validateCanUpdateAccount(zone: Zone,
                                       accountId: AccountId,
                                       // TODO: Add actingAs
                                       publicKey: PublicKey): ValidatedNel[ZoneResponse.Error, Unit] =
    zone.accounts.get(accountId) match {
      case None =>
        Validated.invalidNel(ZoneResponse.Error.accountDoesNotExist)
      case Some(account)
          if !account.ownerMemberIds.exists(
            memberId => zone.members.get(memberId).exists(_.ownerPublicKeys.contains(publicKey))) =>
        Validated.invalidNel(ZoneResponse.Error.accountOwnerKeyMismatch)
      case _ =>
        Validated.valid(())
    }

  private def validateCanDebitAccount(zone: Zone,
                                      accountId: AccountId,
                                      actingAs: MemberId,
                                      publicKey: PublicKey): ValidatedNel[ZoneResponse.Error, Unit] =
    zone.accounts.get(accountId) match {
      case None =>
        Validated.invalidNel(ZoneResponse.Error.accountDoesNotExist)
      case Some(account) if !account.ownerMemberIds.contains(actingAs) =>
        Validated.invalidNel(ZoneResponse.Error.accountOwnerMismatch)
      case _ =>
        zone.members.get(actingAs) match {
          case None =>
            Validated.invalidNel(ZoneResponse.Error.memberDoesNotExist)
          case Some(member) if !member.ownerPublicKeys.contains(publicKey) =>
            Validated.invalidNel(ZoneResponse.Error.memberKeyMismatch)
          case _ =>
            Validated.valid(())
        }
    }

  private def validateFromAndTo(from: AccountId,
                                to: AccountId,
                                zone: Zone): ValidatedNel[ZoneResponse.Error, (AccountId, AccountId)] = {
    val validatedFrom =
      if (!zone.accounts.contains(from))
        Validated.invalidNel(ZoneResponse.Error.sourceAccountDoesNotExist)
      else Validated.valid(from)
    val validatedTo =
      if (!zone.accounts.contains(to))
        Validated.invalidNel(ZoneResponse.Error.destinationAccountDoesNotExist)
      else Validated.valid(to)
    (validatedTo |@| validatedFrom).tupled.andThen {
      case (validTo, validFrom) if validTo == validFrom =>
        Validated.invalidNel(ZoneResponse.Error.reflexiveTransaction)
      case _ =>
        Validated.valid((from, to))
    }
  }

  private def validateValue(from: AccountId,
                            value: BigDecimal,
                            zone: Zone,
                            balances: Map[AccountId, BigDecimal]): ValidatedNel[ZoneResponse.Error, BigDecimal] =
    (if (value.compare(0) == -1)
       Validated.invalidNel(ZoneResponse.Error.negativeTransactionValue)
     else Validated.valid(value)).andThen { value =>
      val updatedSourceBalance = balances(from) - value
      if (updatedSourceBalance < 0 && from != zone.equityAccountId)
        Validated.invalidNel(ZoneResponse.Error.insufficientBalance)
      else
        Validated.valid(value)
    }

  private def validateTag(tag: Option[String]): ValidatedNel[ZoneResponse.Error, Option[String]] =
    tag.map(_.length) match {
      case Some(tagLength) if tagLength > ZoneCommand.MaximumTagLength =>
        Validated.invalidNel(ZoneResponse.Error.tagLengthExceeded)
      case _ =>
        Validated.valid(tag)
    }

  private def validateMetadata(metadata: Option[com.google.protobuf.struct.Struct])
    : ValidatedNel[ZoneResponse.Error, Option[com.google.protobuf.struct.Struct]] =
    metadata.map(_.toByteArray.length) match {
      case Some(metadataSize) if metadataSize > ZoneCommand.MaximumMetadataSize =>
        Validated.invalidNel(ZoneResponse.Error.metadataLengthExceeded)
      case _ =>
        Validated.valid(metadata)
    }

}

class ZoneValidatorActor extends PersistentActor with ActorLogging with AtLeastOnceDelivery with Timers {

  import ZoneValidatorActor.PassivationCountdownActor._

  private[this] val mediator = DistributedPubSub(context.system).mediator
  private[this] val passivationCountdownActor = context.spawn(
    akka.typed.scaladsl.Actor
      .supervise(PassivationCountdownActor.behaviour(self))
      .onFailure[Exception](akka.typed.SupervisorStrategy.restart),
    "passivation-countdown"
  )

  private[this] val id = ZoneId(UUID.fromString(self.path.name))
  private[this] var state =
    ZoneState(zone = None, balances = Map.empty.withDefaultValue(BigDecimal(0)), clientConnections = Map.empty)

  private[this] var nextExpectedCommandSequenceNumbers = Map.empty[ActorPath, Long].withDefaultValue(1L)
  private[this] var messageSequenceNumbers             = Map.empty[ActorPath, Long].withDefaultValue(1L)
  private[this] var pendingDeliveries                  = Map.empty[ActorPath, Set[Long]].withDefaultValue(Set.empty)

  timers.startPeriodicTimer(PublishStatusTimerKey, PublishStatus, 30.seconds)

  override def persistenceId: String = id.persistenceId

  override def postStop(): Unit = {
    super.postStop()
  }

  override def receiveRecover: Receive = {
    case SnapshotOffer(_, ZoneSnapshot(zoneState)) =>
      state = zoneState

    case event: ZoneEvent =>
      state = update(state, event)

    case RecoveryCompleted =>
      state = state.copy(
        clientConnections = Map.empty
      )
  }

  override def receiveCommand: Receive = {
    case PublishStatus =>
      state.zone.foreach(
        zone =>
          mediator ! Publish(
            ZonesMonitorActor.ZoneStatusTopic,
            UpsertActiveZoneSummary(
              self,
              ActiveZoneSummary(
                id,
                zone.members.values.toSet,
                zone.accounts.values.toSet,
                zone.transactions.values.toSet,
                zone.metadata,
                state.clientConnections.values.toSet
              )
            )
        ))

    case RequestPassivate =>
      context.parent ! Passivate(stopMessage = PoisonPill)

    case SaveSnapshotSuccess(metadata) =>
      deleteSnapshots(SnapshotSelectionCriteria(maxSequenceNr = metadata.sequenceNr - 1))

    case GetZoneStateCommand(_) =>
      sender() ! GetZoneStateResponse(state)

    case ZoneCommandEnvelope(_, command, publicKey, correlationId, sequenceNumber, deliveryId) =>
      passivationCountdownActor ! CommandReceivedEvent
      exactlyOnce(sequenceNumber, deliveryId)(
        handleCommand(publicKey, command, correlationId)
      )

    case MessageReceivedConfirmation(deliveryId) =>
      confirmDelivery(deliveryId)
      pendingDeliveries = pendingDeliveries + (sender().path -> (pendingDeliveries(sender().path) - deliveryId))
      if (pendingDeliveries(sender().path).isEmpty)
        pendingDeliveries = pendingDeliveries - sender().path

    case Terminated(clientConnection) =>
      handleQuit(clientConnection) {
        self ! PublishStatus
      }
      nextExpectedCommandSequenceNumbers = nextExpectedCommandSequenceNumbers - clientConnection.path
      messageSequenceNumbers = messageSequenceNumbers - clientConnection.path
      pendingDeliveries(clientConnection.path).foreach(confirmDelivery)
      pendingDeliveries = pendingDeliveries - clientConnection.path
  }

  private[this] def exactlyOnce(sequenceNumber: Long, deliveryId: Long)(body: => Unit): Unit = {
    val nextExpectedCommandSequenceNumber = nextExpectedCommandSequenceNumbers(sender().path)
    if (sequenceNumber <= nextExpectedCommandSequenceNumber)
      sender() ! ZoneCommandReceivedConfirmation(id, deliveryId)
    if (sequenceNumber == nextExpectedCommandSequenceNumber) {
      nextExpectedCommandSequenceNumbers = nextExpectedCommandSequenceNumbers + (sender().path -> (sequenceNumber + 1))
      body
    }
  }

  private[this] def handleCommand(publicKey: PublicKey, command: ZoneCommand, correlationId: Long): Unit =
    command match {
      case EmptyZoneCommand => ()

      case CreateZoneCommand(
          equityOwnerPublicKey,
          equityOwnerName,
          equityOwnerMetadata,
          equityAccountName,
          equityAccountMetadata,
          name,
          metadata
          ) =>
        state.zone match {
          case Some(_) =>
            val sequenceNumber = messageSequenceNumbers(sender().path)
            messageSequenceNumbers = messageSequenceNumbers + (sender().path -> (sequenceNumber + 1))
            deliver(sender().path) { deliveryId =>
              pendingDeliveries = pendingDeliveries + (sender().path -> (pendingDeliveries(sender().path) + deliveryId))
              CreateZoneResponse(Validated.invalidNel(ZoneResponse.Error.zoneAlreadyExists))
            }
          case None =>
            val validatedParams = {
              val validatedEquityOwnerName       = validateTag(equityOwnerName)
              val validatedEquityOwnerMetadata   = validateMetadata(equityOwnerMetadata)
              val validatedEquityAccountName     = validateTag(equityAccountName)
              val validatedEquityAccountMetadata = validateMetadata(equityAccountMetadata)
              val validatedName                  = validateTag(name)
              val validatedMetadata              = validateMetadata(metadata)
              (validatedEquityOwnerName |@| validatedEquityOwnerMetadata |@| validatedEquityAccountName |@|
                validatedEquityAccountMetadata |@| validatedName |@| validatedMetadata).tupled
            }
            validatedParams match {
              case Invalid(errors) =>
                deliverResponse(
                  CreateZoneResponse(Validated.invalid(errors)),
                  correlationId
                )
              case Valid(_) =>
                val equityOwner = Member(
                  MemberId(0),
                  Set(equityOwnerPublicKey),
                  equityOwnerName,
                  equityOwnerMetadata
                )
                val equityAccount = Account(
                  AccountId(0),
                  Set(equityOwner.id),
                  equityAccountName,
                  equityAccountMetadata
                )
                val created = System.currentTimeMillis
                val expires = created + ZoneLifetime.toMillis
                val zone = Zone(
                  id,
                  equityAccount.id,
                  Map(
                    equityOwner.id -> equityOwner
                  ),
                  Map(
                    equityAccount.id -> equityAccount
                  ),
                  Map.empty,
                  created,
                  expires,
                  name,
                  metadata
                )
                acceptCommand(
                  ZoneCreatedEvent(System.currentTimeMillis, zone),
                  CreateZoneResponse(Validated.valid(zone)),
                  correlationId
                )
            }
        }

      case JoinZoneCommand =>
        state.zone match {
          case None =>
            deliverResponse(
              JoinZoneResponse(Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist)),
              correlationId
            )
          case Some(zone) =>
            if (state.clientConnections.contains(sender().path))
              deliverResponse(
                JoinZoneResponse(Validated.invalidNel(ZoneResponse.Error.zoneAlreadyJoined)),
                correlationId
              )
            else
              handleJoin(sender(), publicKey) { state =>
                deliverResponse(
                  JoinZoneResponse(
                    Validated.valid(
                      (
                        zone,
                        state.clientConnections.values.toSet
                      ))),
                  correlationId
                )
                self ! PublishStatus
              }
        }

      case QuitZoneCommand =>
        state.zone match {
          case None =>
            deliverResponse(
              QuitZoneResponse(Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist)),
              correlationId
            )
          case Some(_) =>
            if (!state.clientConnections.contains(sender().path))
              deliverResponse(
                QuitZoneResponse(Validated.invalidNel(ZoneResponse.Error.zoneNotJoined)),
                correlationId
              )
            else
              handleQuit(sender()) {
                deliverResponse(
                  QuitZoneResponse(
                    Validated.valid(())
                  ),
                  correlationId
                )
                self ! PublishStatus
              }
        }

      case ChangeZoneNameCommand(name) =>
        state.zone match {
          case None =>
            deliverResponse(
              ChangeZoneNameResponse(Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist)),
              correlationId
            )
          case Some(_) =>
            val validatedParams = validateTag(name)
            validatedParams match {
              case Invalid(errors) =>
                deliverResponse(
                  ChangeZoneNameResponse(
                    Validated.invalid(errors)
                  ),
                  correlationId
                )
              case Valid(_) =>
                acceptCommand(
                  ZoneNameChangedEvent(System.currentTimeMillis, name),
                  ChangeZoneNameResponse(Validated.valid(())),
                  correlationId,
                  Some(ZoneNameChangedNotification(name))
                )
            }
        }

      case CreateMemberCommand(ownerPublicKeys, name, metadata) =>
        state.zone match {
          case None =>
            deliverResponse(
              CreateMemberResponse(Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist)),
              correlationId
            )
          case Some(zone) =>
            val validatedParams = {
              val validatedMemberId =
                Validated.valid[NonEmptyList[ZoneResponse.Error], MemberId](MemberId(zone.members.size.toLong))
              val validatedOwnerPublicKeys = validatePublicKeys(ownerPublicKeys)
              val validatedName            = validateTag(name)
              val validatedMetadata        = validateMetadata(metadata)
              (validatedMemberId |@| validatedOwnerPublicKeys |@| validatedName |@| validatedMetadata).tupled
            }
            validatedParams match {
              case Invalid(errors) =>
                deliverResponse(
                  CreateMemberResponse(
                    Validated.invalid(errors)
                  ),
                  correlationId
                )
              case Valid(params) =>
                val member = Member.tupled(params)
                acceptCommand(
                  MemberCreatedEvent(System.currentTimeMillis, member),
                  CreateMemberResponse(Validated.valid(member)),
                  correlationId,
                  Some(MemberCreatedNotification(member))
                )
            }
        }

      case UpdateMemberCommand(member) =>
        state.zone match {
          case None =>
            deliverResponse(
              UpdateMemberResponse(Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist)),
              correlationId
            )
          case Some(zone) =>
            val validatedParams = validateCanUpdateMember(zone, member.id, publicKey).andThen { _ =>
              val validatedOwnerPublicKeys = validatePublicKeys(member.ownerPublicKeys)
              val validatedTag             = validateTag(member.name)
              val validatedMetadata        = validateMetadata(member.metadata)
              (validatedOwnerPublicKeys |@| validatedTag |@| validatedMetadata).tupled
            }
            validatedParams match {
              case Invalid(errors) =>
                deliverResponse(
                  UpdateMemberResponse(
                    Validated.invalid(errors)
                  ),
                  correlationId
                )
              case Valid(_) =>
                acceptCommand(
                  MemberUpdatedEvent(System.currentTimeMillis, member),
                  UpdateMemberResponse(Validated.valid(())),
                  correlationId,
                  Some(MemberUpdatedNotification(member))
                )
            }
        }

      case CreateAccountCommand(owners, name, metadata) =>
        state.zone match {
          case None =>
            deliverResponse(
              CreateAccountResponse(Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist)),
              correlationId
            )
          case Some(zone) =>
            val validatedParams = {
              val validatedAccountId =
                Validated.valid[NonEmptyList[ZoneResponse.Error], AccountId](AccountId(zone.accounts.size.toLong))
              val validatedOwnerMemberIds = validateMemberIds(zone, owners)
              val validatedTag            = validateTag(name)
              val validatedMetadata       = validateMetadata(metadata)
              (validatedAccountId |@| validatedOwnerMemberIds |@| validatedTag |@| validatedMetadata).tupled
            }
            validatedParams match {
              case Invalid(errors) =>
                deliverResponse(
                  CreateAccountResponse(
                    Validated.invalid(errors)
                  ),
                  correlationId
                )
              case Valid(params) =>
                val account = Account.tupled(params)
                acceptCommand(
                  AccountCreatedEvent(System.currentTimeMillis, account),
                  CreateAccountResponse(Validated.valid(account)),
                  correlationId,
                  Some(AccountCreatedNotification(account))
                )
            }
        }

      case UpdateAccountCommand(account) =>
        state.zone match {
          case None =>
            deliverResponse(
              UpdateAccountResponse(Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist)),
              correlationId
            )
          case Some(zone) =>
            val validatedParams = validateCanUpdateAccount(zone, account.id, publicKey).andThen { _ =>
              val validatedOwnerMemberIds = validateMemberIds(zone, account.ownerMemberIds)
              val validatedTag            = validateTag(account.name)
              val validatedMetadata       = validateMetadata(account.metadata)
              (validatedOwnerMemberIds |@| validatedTag |@| validatedMetadata).tupled
            }
            validatedParams match {
              case Invalid(errors) =>
                deliverResponse(
                  UpdateAccountResponse(
                    Validated.invalid(errors)
                  ),
                  correlationId
                )
              case Valid(_) =>
                acceptCommand(
                  AccountUpdatedEvent(System.currentTimeMillis, account),
                  UpdateAccountResponse(Validated.valid(())),
                  correlationId,
                  Some(AccountUpdatedNotification(account))
                )
            }
        }

      case AddTransactionCommand(actingAs, from, to, value, description, metadata) =>
        state.zone match {
          case None =>
            deliverResponse(
              AddTransactionResponse(Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist)),
              correlationId
            )
          case Some(zone) =>
            val validatedParams = validateCanDebitAccount(zone, from, actingAs, publicKey)
              .andThen { _ =>
                val validatedFromAndTo   = validateFromAndTo(from, to, zone)
                val validatedDescription = validateTag(description)
                val validatedMetadata    = validateMetadata(metadata)
                (validatedFromAndTo |@| validatedDescription |@| validatedMetadata).tupled
              }
              .andThen(
                _ =>
                  validateValue(from, value, zone, state.balances)
                    .map(
                      (TransactionId(zone.transactions.size.toLong),
                       from,
                       to,
                       _,
                       actingAs,
                       System.currentTimeMillis,
                       description,
                       metadata)))
            validatedParams match {
              case Invalid(errors) =>
                deliverResponse(
                  AddTransactionResponse(Validated.invalid(errors)),
                  correlationId
                )
              case Valid(params) =>
                val transaction = Transaction.tupled(params)
                acceptCommand(
                  TransactionAddedEvent(transaction.created, transaction),
                  AddTransactionResponse(Validated.valid(transaction)),
                  correlationId,
                  Some(TransactionAddedNotification(transaction))
                )
            }
        }
    }

  private[this] def handleJoin(clientConnection: ActorRef, publicKey: PublicKey)(
      onStateUpdate: ZoneState => Unit): Unit =
    persist(ZoneJoinedEvent(System.currentTimeMillis, clientConnection.path, publicKey)) { zoneJoinedEvent =>
      if (state.clientConnections.isEmpty) passivationCountdownActor ! Stop
      context.watch(clientConnection)
      val wasAlreadyPresent = state.clientConnections.values.exists(_ == zoneJoinedEvent.publicKey)
      state = update(state, zoneJoinedEvent)
      onStateUpdate(state)
      if (!wasAlreadyPresent) deliverNotification(ClientJoinedZoneNotification(zoneJoinedEvent.publicKey))
    }

  private[this] def handleQuit(clientConnection: ActorRef)(onStateUpdate: => Unit): Unit =
    persist(ZoneQuitEvent(System.currentTimeMillis, clientConnection.path)) { zoneQuitEvent =>
      val publicKey = state.clientConnections(clientConnection.path)
      state = update(state, zoneQuitEvent)
      onStateUpdate
      val isStillPresent = state.clientConnections.values.exists(_ == publicKey)
      if (!isStillPresent) deliverNotification(ClientQuitZoneNotification(publicKey))
      context.unwatch(clientConnection)
      if (state.clientConnections.isEmpty) passivationCountdownActor ! Start
    }

  private[this] def acceptCommand(event: ZoneEvent,
                                  response: ZoneResponse,
                                  correlationId: Long,
                                  notification: Option[ZoneNotification] = None): Unit =
    persist(event) { event =>
      state = update(state, event)
      if (lastSequenceNr % SnapShotInterval == 0 && lastSequenceNr != 0)
        saveSnapshot(ZoneSnapshot(state))
      deliverResponse(response, correlationId)
      notification.foreach(deliverNotification)
      self ! PublishStatus
    }

  private[this] def deliverResponse(response: ZoneResponse, correlationId: Long): Unit = {
    val sequenceNumber = messageSequenceNumbers(sender().path)
    messageSequenceNumbers = messageSequenceNumbers + (sender().path -> (sequenceNumber + 1))
    deliver(sender().path) { deliveryId =>
      pendingDeliveries = pendingDeliveries + (sender().path -> (pendingDeliveries(sender().path) + deliveryId))
      ZoneResponseEnvelope(
        response,
        correlationId,
        sequenceNumber,
        deliveryId
      )
    }
  }

  private[this] def deliverNotification(notification: ZoneNotification): Unit = {
    state.clientConnections.keys.foreach { clientConnection =>
      val sequenceNumber = messageSequenceNumbers(clientConnection)
      messageSequenceNumbers = messageSequenceNumbers + (clientConnection -> (sequenceNumber + 1))
      deliver(clientConnection) { deliveryId =>
        pendingDeliveries = pendingDeliveries + (sender().path -> (pendingDeliveries(clientConnection) + deliveryId))
        ZoneNotificationEnvelope(id, notification, sequenceNumber, deliveryId)
      }
    }
  }
}
