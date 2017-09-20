package com.dhpcs.liquidity.server.actor

import java.net.InetAddress
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.{InvalidKeySpecException, X509EncodedKeySpec}
import java.time.Instant

import akka.actor._
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Publish
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.Passivate
import akka.persistence._
import akka.serialization.Serialization
import akka.typed
import akka.typed.Behavior
import akka.typed.scaladsl.adapter._
import cats.Cartesian
import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.instances.option._
import cats.instances.set._
import cats.syntax.cartesian._
import com.dhpcs.liquidity.actor.protocol.clientconnection._
import com.dhpcs.liquidity.actor.protocol.zonemonitor._
import com.dhpcs.liquidity.actor.protocol.zonevalidator._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.persistence.zone._
import com.dhpcs.liquidity.server.actor.ZoneValidatorActor._
import com.dhpcs.liquidity.ws.protocol._

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object ZoneValidatorActor {

  def props: Props = Props(new ZoneValidatorActor)

  final val ShardTypeName = "zone-validator"

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case getZoneStateCommand: GetZoneStateCommand => (getZoneStateCommand.zoneId.id.toString, getZoneStateCommand)
    case zoneCommandEnvelope: ZoneCommandEnvelope => (zoneCommandEnvelope.zoneId.id.toString, zoneCommandEnvelope)
  }

  private final val NumberOfShards = 10

  val extractShardId: ShardRegion.ExtractShardId = {
    case GetZoneStateCommand(zoneId)                   => (math.abs(zoneId.id.hashCode) % NumberOfShards).toString
    case ZoneCommandEnvelope(zoneId, _, _, _, _, _, _) => (math.abs(zoneId.id.hashCode) % NumberOfShards).toString
  }

  private case object PublishStatusTimerKey
  private case object PublishStatusTick

  private final val PassivationTimeout = 2.minutes
  private final val SnapShotInterval   = 100
  private final val ZoneLifetime       = 7.days

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

  private def validateMemberIds(zone: Zone,
                                memberIds: Set[MemberId]): ValidatedNel[ZoneResponse.Error, Set[MemberId]] = {
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
                                       publicKey: PublicKey,
                                       actingAs: MemberId,
                                       accountId: AccountId): ValidatedNel[ZoneResponse.Error, Unit] =
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

  private def validateCanDebitAccount(zone: Zone,
                                      publicKey: PublicKey,
                                      actingAs: MemberId,
                                      accountId: AccountId): ValidatedNel[ZoneResponse.Error, Unit] =
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

  private def update(passivationCountdownActor: ActorRef,
                     state: ZoneState,
                     eventEnvelope: ZoneEventEnvelope): ZoneState =
    eventEnvelope.zoneEvent match {
      case EmptyZoneEvent =>
        state
      case zoneCreatedEvent: ZoneCreatedEvent =>
        state.copy(
          zone = Some(zoneCreatedEvent.zone),
          balances = state.balances ++ zoneCreatedEvent.zone.accounts.values.map(_.id -> BigDecimal(0)).toMap
        )
      case ClientJoinedEvent(maybeClientConnectionActorRef) =>
        Cartesian[Option].product(eventEnvelope.publicKey, maybeClientConnectionActorRef) match {
          case None =>
            state
          case Some((publicKey, clientConnectionActorRef)) =>
            if (state.connectedClients.isEmpty) passivationCountdownActor ! PassivationCountdownActor.Stop
            val updatedClientConnections = state.connectedClients + (clientConnectionActorRef -> publicKey)
            state.copy(
              connectedClients = updatedClientConnections
            )
        }
      case ClientQuitEvent(maybeClientConnectionActorRef) =>
        maybeClientConnectionActorRef match {
          case None =>
            state
          case Some(clientConnectionActorRef) =>
            val updatedClientConnections = state.connectedClients - clientConnectionActorRef
            if (updatedClientConnections.isEmpty) passivationCountdownActor ! PassivationCountdownActor.Start
            state.copy(
              connectedClients = updatedClientConnections
            )
        }
      case ZoneNameChangedEvent(name) =>
        state.copy(
          zone = state.zone.map(
            _.copy(
              name = name
            ))
        )
      case MemberCreatedEvent(member) =>
        state.copy(
          zone = state.zone.map(
            _.copy(
              members = state.zone.map(_.members).getOrElse(Map.empty) + (member.id -> member)
            ))
        )
      case MemberUpdatedEvent(member) =>
        state.copy(
          zone = state.zone.map(
            _.copy(
              members = state.zone.map(_.members).getOrElse(Map.empty) + (member.id -> member)
            ))
        )
      case AccountCreatedEvent(account) =>
        state.copy(
          zone = state.zone.map(
            _.copy(
              accounts = state.zone.map(_.accounts).getOrElse(Map.empty) + (account.id -> account)
            )),
          balances = state.balances + (account.id -> BigDecimal(0))
        )
      case AccountUpdatedEvent(_, account) =>
        state.copy(
          zone = state.zone.map(
            _.copy(
              accounts = state.zone.map(_.accounts).getOrElse(Map.empty) + (account.id -> account)
            ))
        )
      case TransactionAddedEvent(transaction) =>
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

  private object PassivationCountdownActor {

    sealed abstract class PassivationCountdownMessage
    case object Start                extends PassivationCountdownMessage
    case object Stop                 extends PassivationCountdownMessage
    case object CommandReceivedEvent extends PassivationCountdownMessage
    case object RequestPassivate     extends PassivationCountdownMessage

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
}

class ZoneValidatorActor extends PersistentActor with ActorLogging with AtLeastOnceDelivery with Timers {

  private[this] val mediator = DistributedPubSub(context.system).mediator
  // TODO: Eliminate when AtLeastOnceDelivery is removed?
  private[this] val passivationCountdownActor = context.spawn(
    akka.typed.scaladsl.Actor
      .supervise(PassivationCountdownActor.behaviour(self))
      .onFailure[Exception](akka.typed.SupervisorStrategy.restart),
    "passivation-countdown"
  )

  private[this] val id    = ZoneId.fromPersistenceId(self.path.name)
  private[this] var state = ZoneState(zone = None, balances = Map.empty, connectedClients = Map.empty)

  private[this] var nextExpectedCommandSequenceNumbers = Map.empty[ActorRef, Long].withDefaultValue(1L)
  private[this] var messageSequenceNumbers             = Map.empty[ActorRef, Long].withDefaultValue(1L)
  private[this] var pendingDeliveries                  = Map.empty[ActorRef, Set[Long]].withDefaultValue(Set.empty)

  timers.startPeriodicTimer(PublishStatusTimerKey, PublishStatusTick, 30.seconds)

  override def persistenceId: String = id.persistenceId

  override def preStart(): Unit = {
    super.preStart()
    log.info(s"Starting")
  }

  override def postStop(): Unit = {
    log.info(s"Stopped")
    super.postStop()
  }

  override def receiveRecover: Receive = {
    case SnapshotOffer(_, ZoneSnapshot(zoneState)) =>
      state = zoneState

    case eventEnvelope: ZoneEventEnvelope =>
      state = update(passivationCountdownActor.toUntyped, state, eventEnvelope)
  }

  override def receiveCommand: Receive = {
    case PublishStatusTick =>
      state.zone.foreach(
        zone =>
          mediator ! Publish(
            ZoneMonitorActor.ZoneStatusTopic,
            UpsertActiveZoneSummary(
              self,
              ActiveZoneSummary(
                id,
                zone.members.values.toSet,
                zone.accounts.values.toSet,
                zone.transactions.values.toSet,
                zone.metadata,
                state.connectedClients.values.toSet
              )
            )
        ))

    case PassivationCountdownActor.RequestPassivate =>
      context.parent ! Passivate(stopMessage = PoisonPill)

    case SaveSnapshotSuccess(metadata) =>
      deleteSnapshots(SnapshotSelectionCriteria(maxSequenceNr = metadata.sequenceNr - 1))

    case GetZoneStateCommand(_) =>
      sender() ! GetZoneStateResponse(state)

    case ZoneCommandEnvelope(_, remoteAddress, publicKey, correlationId, sequenceNumber, deliveryId, command) =>
      passivationCountdownActor ! PassivationCountdownActor.CommandReceivedEvent
      context.watch(sender())
      val nextExpectedCommandSequenceNumber = nextExpectedCommandSequenceNumbers(sender())
      if (sequenceNumber <= nextExpectedCommandSequenceNumber)
        sender() ! ZoneCommandReceivedConfirmation(id, deliveryId)
      if (sequenceNumber == nextExpectedCommandSequenceNumber) {
        nextExpectedCommandSequenceNumbers = nextExpectedCommandSequenceNumbers + (sender() -> (sequenceNumber + 1))
        handleCommand(remoteAddress, publicKey, correlationId, command)
      }

    case MessageReceivedConfirmation(deliveryId) =>
      confirmDelivery(deliveryId)
      pendingDeliveries = pendingDeliveries + (sender() -> (pendingDeliveries(sender()) - deliveryId))
      if (pendingDeliveries(sender()).isEmpty)
        pendingDeliveries = pendingDeliveries - sender()

    case Terminated(clientConnection) =>
      state.connectedClients
        .get(clientConnection)
        .foreach(
          publicKey =>
            acceptCommand(
              InetAddress.getLoopbackAddress,
              publicKey,
              ClientQuitEvent(Some(sender())),
              correlationId = -1
          ))
      nextExpectedCommandSequenceNumbers = nextExpectedCommandSequenceNumbers - clientConnection
      messageSequenceNumbers = messageSequenceNumbers - clientConnection
      pendingDeliveries(clientConnection).foreach(confirmDelivery)
      pendingDeliveries = pendingDeliveries - clientConnection
  }

  private[this] def handleCommand(remoteAddress: InetAddress,
                                  publicKey: PublicKey,
                                  correlationId: Long,
                                  command: ZoneCommand): Unit =
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
            val sequenceNumber = messageSequenceNumbers(sender())
            messageSequenceNumbers = messageSequenceNumbers + (sender() -> (sequenceNumber + 1))
            deliver(sender().path) { deliveryId =>
              pendingDeliveries = pendingDeliveries + (sender() -> (pendingDeliveries(sender()) + deliveryId))
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
                  MemberId(0.toString),
                  Set(equityOwnerPublicKey),
                  equityOwnerName,
                  equityOwnerMetadata
                )
                val equityAccount = Account(
                  AccountId(0.toString),
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
                  remoteAddress,
                  publicKey,
                  ZoneCreatedEvent(zone),
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
          case Some(_) =>
            if (state.connectedClients.contains(sender()))
              deliverResponse(
                JoinZoneResponse(Validated.invalidNel(ZoneResponse.Error.zoneAlreadyJoined)),
                correlationId
              )
            else {
              acceptCommand(
                remoteAddress,
                publicKey,
                ClientJoinedEvent(Some(sender())),
                correlationId
              )
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
            if (!state.connectedClients.contains(sender()))
              deliverResponse(
                QuitZoneResponse(Validated.invalidNel(ZoneResponse.Error.zoneNotJoined)),
                correlationId
              )
            else {
              acceptCommand(
                remoteAddress,
                publicKey,
                ClientQuitEvent(Some(sender())),
                correlationId
              )
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
                  remoteAddress,
                  publicKey,
                  ZoneNameChangedEvent(name),
                  correlationId
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
                Validated.valid[NonEmptyList[ZoneResponse.Error], MemberId](MemberId(zone.members.size.toString))
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
                  remoteAddress,
                  publicKey,
                  MemberCreatedEvent(member),
                  correlationId
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
                  remoteAddress,
                  publicKey,
                  MemberUpdatedEvent(member),
                  correlationId
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
                Validated.valid[NonEmptyList[ZoneResponse.Error], AccountId](AccountId(zone.accounts.size.toString))
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
                  remoteAddress,
                  publicKey,
                  AccountCreatedEvent(account),
                  correlationId
                )
            }
        }

      case UpdateAccountCommand(actingAs, account) =>
        state.zone match {
          case None =>
            deliverResponse(
              UpdateAccountResponse(Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist)),
              correlationId
            )
          case Some(zone) =>
            val validatedParams = validateCanUpdateAccount(zone, publicKey, actingAs, account.id).andThen { _ =>
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
                  remoteAddress,
                  publicKey,
                  AccountUpdatedEvent(Some(actingAs), account),
                  correlationId
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
            val validatedParams = validateCanDebitAccount(zone, publicKey, actingAs, from)
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
                      (TransactionId(zone.transactions.size.toString),
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
                  remoteAddress,
                  publicKey,
                  TransactionAddedEvent(transaction),
                  correlationId
                )
            }
        }
    }

  private[this] def acceptCommand(remoteAddress: InetAddress,
                                  publicKey: PublicKey,
                                  event: ZoneEvent,
                                  correlationId: Long): Unit =
    persist(
      ZoneEventEnvelope(
        Some(remoteAddress),
        Some(publicKey),
        timestamp = Instant.now(),
        event
      )) { zoneEventEnvelope =>
      state = update(passivationCountdownActor.toUntyped, state, zoneEventEnvelope)
      if (lastSequenceNr % SnapShotInterval == 0 && lastSequenceNr != 0)
        saveSnapshot(ZoneSnapshot(state))
      val response = event match {
        case EmptyZoneEvent =>
          EmptyZoneResponse
        case ZoneCreatedEvent(zone) =>
          CreateZoneResponse(Validated.valid(zone))
        case ClientJoinedEvent(_) =>
          JoinZoneResponse(
            Validated.valid(
              (
                state.zone.get,
                state.connectedClients.map {
                  case (clientConnectionActorRef, _publicKey) =>
                    Serialization.serializedActorPath(clientConnectionActorRef) -> _publicKey
                }
              )))
        case ClientQuitEvent(_) =>
          QuitZoneResponse(Validated.valid(()))
        case ZoneNameChangedEvent(_) =>
          ChangeZoneNameResponse(Validated.valid(()))
        case MemberCreatedEvent(member) =>
          CreateMemberResponse(Validated.valid(member))
        case MemberUpdatedEvent(_) =>
          UpdateMemberResponse(Validated.valid(()))
        case AccountCreatedEvent(account) =>
          CreateAccountResponse(Validated.valid(account))
        case AccountUpdatedEvent(_, _) =>
          UpdateAccountResponse(Validated.valid(()))
        case TransactionAddedEvent(transaction) =>
          AddTransactionResponse(Validated.valid(transaction))
      }
      deliverResponse(response, correlationId)
      val notification = event match {
        case EmptyZoneEvent =>
          None
        case ZoneCreatedEvent(_) =>
          None
        case ClientJoinedEvent(maybeClientConnectionActorRef) =>
          maybeClientConnectionActorRef.map(clientConnectionActorRef =>
            ClientJoinedNotification(Serialization.serializedActorPath(clientConnectionActorRef), publicKey))
        case ClientQuitEvent(maybeClientConnectionActorRef) =>
          maybeClientConnectionActorRef.map(clientConnectionActorRef =>
            ClientQuitNotification(Serialization.serializedActorPath(clientConnectionActorRef), publicKey))
        case ZoneNameChangedEvent(name) =>
          Some(ZoneNameChangedNotification(name))
        case MemberCreatedEvent(member) =>
          Some(MemberCreatedNotification(member))
        case MemberUpdatedEvent(member) =>
          Some(MemberUpdatedNotification(member))
        case AccountCreatedEvent(account) =>
          Some(AccountCreatedNotification(account))
        case AccountUpdatedEvent(None, account) =>
          Some(AccountUpdatedNotification(account.ownerMemberIds.head, account))
        case AccountUpdatedEvent(Some(actingAs), account) =>
          Some(AccountUpdatedNotification(actingAs, account))
        case TransactionAddedEvent(transaction) =>
          Some(TransactionAddedNotification(transaction))
      }
      notification.foreach(deliverNotification)
      self ! PublishStatusTick
    }

  private[this] def deliverResponse(response: ZoneResponse, correlationId: Long): Unit = {
    val sequenceNumber = messageSequenceNumbers(sender())
    messageSequenceNumbers = messageSequenceNumbers + (sender() -> (sequenceNumber + 1))
    deliver(sender().path) { deliveryId =>
      pendingDeliveries = pendingDeliveries + (sender() -> (pendingDeliveries(sender()) + deliveryId))
      ZoneResponseEnvelope(
        correlationId,
        sequenceNumber,
        deliveryId,
        response
      )
    }
  }

  private[this] def deliverNotification(notification: ZoneNotification): Unit =
    state.connectedClients.keys.foreach { clientConnection =>
      val sequenceNumber = messageSequenceNumbers(clientConnection)
      messageSequenceNumbers = messageSequenceNumbers + (clientConnection -> (sequenceNumber + 1))
      deliver(clientConnection.path) { deliveryId =>
        pendingDeliveries = pendingDeliveries + (sender() -> (pendingDeliveries(clientConnection) + deliveryId))
        ZoneNotificationEnvelope(id, sequenceNumber, deliveryId, notification)
      }
    }

}
