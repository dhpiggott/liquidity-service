package com.dhpcs.liquidity.server.actor

import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.{InvalidKeySpecException, X509EncodedKeySpec}
import java.time.Instant
import java.util.UUID

import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Publish
import akka.event.Logging
import akka.typed.cluster.ActorRefResolver
import akka.typed.cluster.sharding.{EntityTypeKey, ShardingMessageExtractor}
import akka.typed.persistence.scaladsl.PersistentActor
import akka.typed.persistence.scaladsl.PersistentActor._
import akka.typed.scaladsl.adapter._
import akka.typed.scaladsl.{Actor, ActorContext}
import akka.typed.{ActorRef, Behavior, PostStop, Terminated}
import cats.Semigroupal
import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.instances.option._
import cats.instances.set._
import cats.syntax.apply._
import cats.syntax.validated._
import com.dhpcs.liquidity.actor.protocol.clientconnection._
import com.dhpcs.liquidity.actor.protocol.zonemonitor.{ActiveZoneSummary, UpsertActiveZoneSummary}
import com.dhpcs.liquidity.actor.protocol.zonevalidator._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.persistence.zone._
import com.dhpcs.liquidity.ws.protocol._

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object ZoneValidatorActor {

  final val ShardingTypeName = EntityTypeKey[SerializableZoneValidatorMessage]("zoneValidator")

  private final val MaxNumberOfShards = 10

  val messageExtractor: ShardingMessageExtractor[SerializableZoneValidatorMessage, SerializableZoneValidatorMessage] =
    ShardingMessageExtractor.noEnvelope(
      MaxNumberOfShards, {
        // This has to be part of SerializableZoneValidatorMessage because the akka-typed sharding API requires that
        // the hand-off stop-message is a subtype of the ShardingMessageExtractor Envelope type. Of course, hand-off
        // stop-messages are sent directly to the entity to be stopped, so this extractor won't actually encounter them.
        case StopZone                                   => throw new IllegalArgumentException("Received StopZone")
        case GetZoneStateCommand(_, zoneId)             => zoneId.id
        case ZoneCommandEnvelope(_, zoneId, _, _, _, _) => zoneId.id
      }
    )

  private case object PublishStatusTimerKey

  private final val PassivationTimeout = 2.minutes
  // TODO: Re-enable snapshots once the akka-typed API supports it
  // private final val SnapShotInterval   = 100
  private final val ZoneLifetime = 7.days

  private object PassivationCountdownActor {

    sealed abstract class PassivationCountdownMessage
    case object Start                extends PassivationCountdownMessage
    case object Stop                 extends PassivationCountdownMessage
    case object CommandReceivedEvent extends PassivationCountdownMessage
    case object ReceiveTimeout       extends PassivationCountdownMessage

    def behavior(zoneValidator: ActorRef[ZoneValidatorMessage]): Behavior[PassivationCountdownMessage] =
      Actor.deferred { context =>
        context.self ! Start
        Actor.immutable[PassivationCountdownMessage]((_, message) =>
          message match {
            case Start =>
              context.setReceiveTimeout(PassivationTimeout, ReceiveTimeout)
              Actor.same

            case Stop =>
              context.cancelReceiveTimeout()
              Actor.same

            case CommandReceivedEvent =>
              Actor.same

            case ReceiveTimeout =>
              zoneValidator ! StopZone
              Actor.same
        })
      }

  }

  private object ClientConnectionWatcherActor {

    sealed abstract class ClientConnectionWatcherMessage
    final case class Watch(clientConnection: ActorRef[SerializableClientConnectionMessage])
        extends ClientConnectionWatcherMessage
    final case class Unwatch(clientConnection: ActorRef[SerializableClientConnectionMessage])
        extends ClientConnectionWatcherMessage

    def behavior(zoneValidator: ActorRef[ZoneValidatorMessage]): Behavior[ClientConnectionWatcherMessage] =
      Actor.immutable[ClientConnectionWatcherMessage]((context, message) =>
        message match {
          case Watch(clientConnection) =>
            context.watch(clientConnection)
            Actor.same

          case Unwatch(clientConnection) =>
            context.watch(clientConnection)
            Actor.same
      }) onSignal {
        case (_, Terminated(ref)) =>
          zoneValidator ! RemoveClient(ref.upcast)
          Actor.same
      }

  }

  def shardingBehavior: Behavior[SerializableZoneValidatorMessage] =
    Actor
      .deferred[ZoneValidatorMessage] { context =>
        val log = Logging(context.system.toUntyped, context.self.toUntyped)
        log.info("Starting")
        val persistenceId               = context.self.path.name
        val notificationSequenceNumbers = mutable.Map.empty[ActorRef[SerializableClientConnectionMessage], Long]
        val mediator                    = DistributedPubSub(context.system.toUntyped).mediator
        // Workarounds for the limitation described in https://github.com/akka/akka/pull/23674
        // TODO: Remove these once that limitation is resolved
        val passivationCountdown =
          context.spawn(PassivationCountdownActor.behavior(context.self), "passivationCountdown")
        val clientConnectionWatcher =
          context.spawn(ClientConnectionWatcherActor.behavior(context.self), "clientConnectionWatcher")
        implicit val resolver: ActorRefResolver = ActorRefResolver(context.system)
        val zoneValidator = context.spawnAnonymous(
          persistentBehavior(ZoneId.fromPersistenceId(persistenceId),
                             notificationSequenceNumbers,
                             mediator,
                             passivationCountdown,
                             clientConnectionWatcher)
        )
        context.watch(zoneValidator)
        Actor.withTimers { timers =>
          timers.startPeriodicTimer(PublishStatusTimerKey, PublishZoneStatusTick, 30.seconds)
          Actor.immutable[ZoneValidatorMessage] { (_, zoneValidatorMessage) =>
            zoneValidator ! zoneValidatorMessage
            Actor.same
          } onSignal {
            case (_, Terminated(_)) =>
              Actor.stopped

            case (_, PostStop) =>
              log.info("Stopped")
              Actor.same
          }
        }
      }
      .narrow[SerializableZoneValidatorMessage]

  private def persistentBehavior(
      id: ZoneId,
      notificationSequenceNumbers: mutable.Map[ActorRef[SerializableClientConnectionMessage], Long],
      mediator: ActorRef[Publish],
      passivationCountdown: ActorRef[PassivationCountdownActor.PassivationCountdownMessage],
      clientConnectionWatcher: ActorRef[ClientConnectionWatcherActor.ClientConnectionWatcherMessage])(
      implicit resolver: ActorRefResolver): Behavior[ZoneValidatorMessage] =
    PersistentActor.immutable[ZoneValidatorMessage, ZoneEventEnvelope, ZoneState](
      persistenceId = id.persistenceId,
      initialState = ZoneState(zone = None, balances = Map.empty, connectedClients = Map.empty),
      commandHandler = CommandHandler[ZoneValidatorMessage, ZoneEventEnvelope, ZoneState]((context, state, command) =>
        command match {
          case PublishZoneStatusTick =>
            state.zone.foreach(
              zone =>
                mediator ! Publish(
                  ZoneMonitorActor.ZoneStatusTopic,
                  UpsertActiveZoneSummary(
                    context.self,
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
            Effect.none

          case StopZone =>
            Effect.stop

          case GetZoneStateCommand(replyTo, _) =>
            replyTo ! state
            Effect.none

          case zoneCommandEnvelope: ZoneCommandEnvelope =>
            passivationCountdown ! PassivationCountdownActor.CommandReceivedEvent
            handleCommand(context, id, notificationSequenceNumbers, state, zoneCommandEnvelope)

          case RemoveClient(clientConnection) =>
            state.connectedClients.get(clientConnection) match {
              case None =>
                Effect.none
              case Some(publicKey) =>
                Effect
                  .persist(
                    ZoneEventEnvelope(
                      remoteAddress = None,
                      Some(publicKey),
                      timestamp = Instant.now(),
                      ClientQuitEvent(Some(resolver.toSerializationFormat(clientConnection)))
                    )
                  )
                  .andThen(state =>
                    deliverNotification(
                      context.self,
                      id,
                      state.connectedClients.keys,
                      notificationSequenceNumbers,
                      ClientQuitNotification(
                        ActorRefResolver(context.system).toSerializationFormat(clientConnection),
                        publicKey
                      )
                  ))
            }
      }),
      eventHandler(notificationSequenceNumbers, passivationCountdown, clientConnectionWatcher)
    )

  private def handleCommand(
      context: ActorContext[ZoneValidatorMessage],
      id: ZoneId,
      notificationSequenceNumbers: mutable.Map[ActorRef[SerializableClientConnectionMessage], Long],
      state: ZoneState,
      zoneCommandEnvelope: ZoneCommandEnvelope)(
      implicit resolver: ActorRefResolver): Effect[ZoneEventEnvelope, ZoneState] =
    zoneCommandEnvelope.zoneCommand match {
      case EmptyZoneCommand =>
        Effect.none

      case CreateZoneCommand(
          equityOwnerPublicKey,
          equityOwnerName,
          equityOwnerMetadata,
          equityAccountName,
          equityAccountMetadata,
          name,
          metadata
          ) =>
        val validatedEquityOwnerName = validateTag(equityOwnerName)
        val validatedParams = {
          val validatedEquityOwnerMetadata   = validateMetadata(equityOwnerMetadata)
          val validatedEquityAccountName     = validateTag(equityAccountName)
          val validatedEquityAccountMetadata = validateMetadata(equityAccountMetadata)
          val validatedName                  = validateTag(name)
          val validatedMetadata              = validateMetadata(metadata)
          (validatedEquityOwnerName,
           validatedEquityOwnerMetadata,
           validatedEquityAccountName,
           validatedEquityAccountMetadata,
           validatedName,
           validatedMetadata).tupled
        }
        validatedParams match {
          case Invalid(errors) =>
            deliverResponse(
              context.self,
              zoneCommandEnvelope.replyTo,
              zoneCommandEnvelope.correlationId,
              CreateZoneResponse(Validated.invalid(errors))
            )
            Effect.none
          case Valid(_) =>
            state.zone match {
              case Some(zone) =>
                // We already accepted the command; this was just a redelivery
                deliverResponse(
                  context.self,
                  zoneCommandEnvelope.replyTo,
                  zoneCommandEnvelope.correlationId,
                  CreateZoneResponse(zone.valid)
                )
                Effect.none
              case None =>
                val equityOwner = Member(
                  MemberId(UUID.randomUUID.toString),
                  Set(equityOwnerPublicKey),
                  equityOwnerName,
                  equityOwnerMetadata
                )
                val equityAccount = Account(
                  AccountId(UUID.randomUUID.toString),
                  Set(equityOwner.id),
                  equityAccountName,
                  equityAccountMetadata
                )
                val created = System.currentTimeMillis
                val expires = created + ZoneLifetime.toMillis
                val zone = Zone(
                  id,
                  equityAccount.id,
                  members = Map(equityOwner.id    -> equityOwner),
                  accounts = Map(equityAccount.id -> equityAccount),
                  transactions = Map.empty,
                  created,
                  expires,
                  name,
                  metadata
                )
                acceptCommand(
                  context.self,
                  id,
                  resolver,
                  notificationSequenceNumbers,
                  zoneCommandEnvelope,
                  ZoneCreatedEvent(zone)
                )
            }
        }

      case JoinZoneCommand =>
        state.zone match {
          case None =>
            deliverResponse(
              context.self,
              zoneCommandEnvelope.replyTo,
              zoneCommandEnvelope.correlationId,
              JoinZoneResponse(Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist))
            )
            Effect.none
          case Some(zone) =>
            if (state.connectedClients.contains(zoneCommandEnvelope.replyTo.upcast)) {
              // We already accepted the command; this was just a redelivery
              deliverResponse(
                context.self,
                zoneCommandEnvelope.replyTo,
                zoneCommandEnvelope.correlationId,
                JoinZoneResponse((zone, state.connectedClients.map {
                  case (clientConnection, _publicKey) =>
                    resolver.toSerializationFormat(clientConnection) -> _publicKey
                }).valid)
              )
              Effect.none
            } else
              acceptCommand(
                context.self,
                id,
                resolver,
                notificationSequenceNumbers,
                zoneCommandEnvelope,
                ClientJoinedEvent(Some(resolver.toSerializationFormat(zoneCommandEnvelope.replyTo)))
              )
        }

      case QuitZoneCommand =>
        state.zone match {
          case None =>
            deliverResponse(
              context.self,
              zoneCommandEnvelope.replyTo,
              zoneCommandEnvelope.correlationId,
              QuitZoneResponse(Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist))
            )
            Effect.none
          case Some(_) =>
            if (!state.connectedClients.contains(zoneCommandEnvelope.replyTo.upcast)) {
              // We already accepted the command; this was just a redelivery
              deliverResponse(
                context.self,
                zoneCommandEnvelope.replyTo,
                zoneCommandEnvelope.correlationId,
                QuitZoneResponse(().valid)
              )
              Effect.none
            } else
              acceptCommand(
                context.self,
                id,
                resolver,
                notificationSequenceNumbers,
                zoneCommandEnvelope,
                ClientQuitEvent(Some(resolver.toSerializationFormat(zoneCommandEnvelope.replyTo)))
              )
        }

      case ChangeZoneNameCommand(name) =>
        state.zone match {
          case None =>
            deliverResponse(
              context.self,
              zoneCommandEnvelope.replyTo,
              zoneCommandEnvelope.correlationId,
              ChangeZoneNameResponse(Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist))
            )
            Effect.none
          case Some(zone) =>
            val validatedParams = validateTag(name)
            validatedParams match {
              case Invalid(errors) =>
                deliverResponse(
                  context.self,
                  zoneCommandEnvelope.replyTo,
                  zoneCommandEnvelope.correlationId,
                  ChangeZoneNameResponse(
                    Validated.invalid(errors)
                  )
                )
                Effect.none
              case Valid(_) =>
                if (zone.name == name) {
                  // We probably already accepted the command and this was just a redelivery. In any case, we don't
                  // need to persist anything.
                  deliverResponse(
                    context.self,
                    zoneCommandEnvelope.replyTo,
                    zoneCommandEnvelope.correlationId,
                    ChangeZoneNameResponse(().valid)
                  )
                  Effect.none
                } else
                  acceptCommand(
                    context.self,
                    id,
                    resolver,
                    notificationSequenceNumbers,
                    zoneCommandEnvelope,
                    ZoneNameChangedEvent(name)
                  )
            }
        }

      case CreateMemberCommand(ownerPublicKeys, name, metadata) =>
        state.zone match {
          case None =>
            deliverResponse(
              context.self,
              zoneCommandEnvelope.replyTo,
              zoneCommandEnvelope.correlationId,
              CreateMemberResponse(Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist))
            )
            Effect.none
          case Some(zone) =>
            val memberId = MemberId(zone.members.size.toString)
            val validatedParams = {
              val validatedMemberId        = memberId.valid[NonEmptyList[ZoneResponse.Error]]
              val validatedOwnerPublicKeys = validatePublicKeys(ownerPublicKeys)
              val validatedName            = validateTag(name)
              val validatedMetadata        = validateMetadata(metadata)
              (validatedMemberId, validatedOwnerPublicKeys, validatedName, validatedMetadata).tupled
            }
            validatedParams match {
              case Invalid(errors) =>
                deliverResponse(
                  context.self,
                  zoneCommandEnvelope.replyTo,
                  zoneCommandEnvelope.correlationId,
                  CreateMemberResponse(
                    Validated.invalid(errors)
                  )
                )
                Effect.none
              case Valid(params) =>
                acceptCommand(
                  context.self,
                  id,
                  resolver,
                  notificationSequenceNumbers,
                  zoneCommandEnvelope,
                  MemberCreatedEvent(Member.tupled(params))
                )
            }
        }

      case UpdateMemberCommand(member) =>
        state.zone match {
          case None =>
            deliverResponse(
              context.self,
              zoneCommandEnvelope.replyTo,
              zoneCommandEnvelope.correlationId,
              UpdateMemberResponse(Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist))
            )
            Effect.none
          case Some(zone) =>
            val validatedParams = validateCanUpdateMember(zone, member.id, zoneCommandEnvelope.publicKey).andThen { _ =>
              val validatedOwnerPublicKeys = validatePublicKeys(member.ownerPublicKeys)
              val validatedTag             = validateTag(member.name)
              val validatedMetadata        = validateMetadata(member.metadata)
              (validatedOwnerPublicKeys, validatedTag, validatedMetadata).tupled
            }
            validatedParams match {
              case Invalid(errors) =>
                deliverResponse(
                  context.self,
                  zoneCommandEnvelope.replyTo,
                  zoneCommandEnvelope.correlationId,
                  UpdateMemberResponse(
                    Validated.invalid(errors)
                  )
                )
                Effect.none
              case Valid(_) =>
                if (member == zone.members(member.id)) {
                  // We probably already accepted the command and this was just a redelivery. In any case, we don't
                  // need to persist anything.
                  deliverResponse(
                    context.self,
                    zoneCommandEnvelope.replyTo,
                    zoneCommandEnvelope.correlationId,
                    UpdateMemberResponse(().valid)
                  )
                  Effect.none
                } else
                  acceptCommand(
                    context.self,
                    id,
                    resolver,
                    notificationSequenceNumbers,
                    zoneCommandEnvelope,
                    MemberUpdatedEvent(member)
                  )
            }
        }

      case CreateAccountCommand(owners, name, metadata) =>
        state.zone match {
          case None =>
            deliverResponse(
              context.self,
              zoneCommandEnvelope.replyTo,
              zoneCommandEnvelope.correlationId,
              CreateAccountResponse(Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist))
            )
            Effect.none
          case Some(zone) =>
            val accountId = AccountId(zone.accounts.size.toString)
            val validatedParams = {
              val validatedAccountId      = accountId.valid[NonEmptyList[ZoneResponse.Error]]
              val validatedOwnerMemberIds = validateMemberIds(zone, owners)
              val validatedTag            = validateTag(name)
              val validatedMetadata       = validateMetadata(metadata)
              (validatedAccountId, validatedOwnerMemberIds, validatedTag, validatedMetadata).tupled
            }
            validatedParams match {
              case Invalid(errors) =>
                deliverResponse(
                  context.self,
                  zoneCommandEnvelope.replyTo,
                  zoneCommandEnvelope.correlationId,
                  CreateAccountResponse(
                    Validated.invalid(errors)
                  )
                )
                Effect.none
              case Valid(params) =>
                acceptCommand(
                  context.self,
                  id,
                  resolver,
                  notificationSequenceNumbers,
                  zoneCommandEnvelope,
                  AccountCreatedEvent(Account.tupled(params))
                )
            }
        }

      case UpdateAccountCommand(actingAs, account) =>
        state.zone match {
          case None =>
            deliverResponse(
              context.self,
              zoneCommandEnvelope.replyTo,
              zoneCommandEnvelope.correlationId,
              UpdateAccountResponse(Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist))
            )
            Effect.none
          case Some(zone) =>
            val validatedParams =
              validateCanUpdateAccount(zone, zoneCommandEnvelope.publicKey, actingAs, account.id).andThen { _ =>
                val validatedOwnerMemberIds = validateMemberIds(zone, account.ownerMemberIds)
                val validatedTag            = validateTag(account.name)
                val validatedMetadata       = validateMetadata(account.metadata)
                (validatedOwnerMemberIds, validatedTag, validatedMetadata).tupled
              }
            validatedParams match {
              case Invalid(errors) =>
                deliverResponse(
                  context.self,
                  zoneCommandEnvelope.replyTo,
                  zoneCommandEnvelope.correlationId,
                  UpdateAccountResponse(
                    Validated.invalid(errors)
                  )
                )
                Effect.none
              case Valid(_) =>
                if (account == zone.accounts(account.id)) {
                  // We probably already accepted the command and this was just a redelivery. In any case, we don't
                  // need to persist anything.
                  deliverResponse(
                    context.self,
                    zoneCommandEnvelope.replyTo,
                    zoneCommandEnvelope.correlationId,
                    UpdateAccountResponse(().valid)
                  )
                  Effect.none
                } else
                  acceptCommand(
                    context.self,
                    id,
                    resolver,
                    notificationSequenceNumbers,
                    zoneCommandEnvelope,
                    AccountUpdatedEvent(Some(actingAs), account)
                  )
            }
        }

      case AddTransactionCommand(actingAs, from, to, value, description, metadata) =>
        state.zone match {
          case None =>
            deliverResponse(
              context.self,
              zoneCommandEnvelope.replyTo,
              zoneCommandEnvelope.correlationId,
              AddTransactionResponse(Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist))
            )
            Effect.none
          case Some(zone) =>
            val transactionId = TransactionId(zone.transactions.size.toString)
            val validatedParams = validateCanDebitAccount(zone, zoneCommandEnvelope.publicKey, actingAs, from)
              .andThen { _ =>
                val validatedFromAndTo   = validateFromAndTo(from, to, zone)
                val validatedDescription = validateTag(description)
                val validatedMetadata    = validateMetadata(metadata)
                (validatedFromAndTo, validatedDescription, validatedMetadata).tupled
              }
              .andThen(_ =>
                validateValue(from, value, zone, state.balances)
                  .map((transactionId, from, to, _, actingAs, System.currentTimeMillis, description, metadata)))
            validatedParams match {
              case Invalid(errors) =>
                deliverResponse(
                  context.self,
                  zoneCommandEnvelope.replyTo,
                  zoneCommandEnvelope.correlationId,
                  AddTransactionResponse(Validated.invalid(errors))
                )
                Effect.none
              case Valid(params) =>
                acceptCommand(
                  context.self,
                  id,
                  resolver,
                  notificationSequenceNumbers,
                  zoneCommandEnvelope,
                  TransactionAddedEvent(Transaction.tupled(params))
                )
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
        .foldLeft(Set.empty[PublicKey].valid[NonEmptyList[ZoneResponse.Error]])(
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
        .foldLeft(Set.empty[MemberId].valid[NonEmptyList[ZoneResponse.Error]])(
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
    (validatedTo, validatedFrom).tupled.andThen {
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

  private def acceptCommand(
      self: ActorRef[ZoneValidatorMessage],
      id: ZoneId,
      resolver: ActorRefResolver,
      notificationSequenceNumbers: mutable.Map[ActorRef[SerializableClientConnectionMessage], Long],
      zoneCommandEnvelope: ZoneCommandEnvelope,
      event: ZoneEvent): Effect[ZoneEventEnvelope, ZoneState] =
    Effect
      .persist[ZoneEventEnvelope, ZoneState](
        ZoneEventEnvelope(
          Some(zoneCommandEnvelope.remoteAddress),
          Some(zoneCommandEnvelope.publicKey),
          timestamp = Instant.now(),
          event
        ))
      .andThen(state =>
        deliverResponse(
          self,
          zoneCommandEnvelope.replyTo,
          zoneCommandEnvelope.correlationId,
          event match {
            case EmptyZoneEvent =>
              EmptyZoneResponse

            case ZoneCreatedEvent(zone) =>
              CreateZoneResponse(Validated.valid(zone))

            case ClientJoinedEvent(_) =>
              JoinZoneResponse(
                Validated.valid((
                  state.zone.get,
                  state.connectedClients.map {
                    case (clientConnection, _publicKey) =>
                      resolver.toSerializationFormat(clientConnection) -> _publicKey
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
      ))
      .andThen(
        self ! PublishZoneStatusTick
      )
      .andThen(state =>
        (event match {
          case EmptyZoneEvent =>
            None

          case ZoneCreatedEvent(_) =>
            None

          case ClientJoinedEvent(maybeActorRefString) =>
            maybeActorRefString.map(actorRefString =>
              ClientJoinedNotification(connectionId = actorRefString, zoneCommandEnvelope.publicKey))

          case ClientQuitEvent(maybeActorRefString) =>
            maybeActorRefString.map(actorRefString =>
              ClientQuitNotification(connectionId = actorRefString, zoneCommandEnvelope.publicKey))

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
        }).foreach(
          deliverNotification(self, id, state.connectedClients.keys, notificationSequenceNumbers, _)
      ))

  private def deliverResponse(self: ActorRef[ZoneValidatorMessage],
                              clientConnection: ActorRef[ZoneResponseEnvelope],
                              correlationId: Long,
                              response: ZoneResponse): Unit =
    clientConnection ! ZoneResponseEnvelope(
      self,
      correlationId,
      response
    )

  private def deliverNotification(
      self: ActorRef[ZoneValidatorMessage],
      id: ZoneId,
      clientConnections: Iterable[ActorRef[SerializableClientConnectionMessage]],
      notificationSequenceNumbers: mutable.Map[ActorRef[SerializableClientConnectionMessage], Long],
      notification: ZoneNotification): Unit =
    clientConnections.foreach { clientConnection =>
      val sequenceNumber = notificationSequenceNumbers(clientConnection)
      clientConnection ! ZoneNotificationEnvelope(self, id, sequenceNumber, notification)
      val nextSequenceNumber = sequenceNumber + 1
      notificationSequenceNumbers += clientConnection -> nextSequenceNumber
    }

  private def eventHandler(
      notificationSequenceNumbers: mutable.Map[ActorRef[SerializableClientConnectionMessage], Long],
      passivationCountdown: ActorRef[PassivationCountdownActor.PassivationCountdownMessage],
      clientConnectionWatcher: ActorRef[ClientConnectionWatcherActor.ClientConnectionWatcherMessage])(
      state: ZoneState,
      event: ZoneEventEnvelope)(implicit resolver: ActorRefResolver): ZoneState =
    event.zoneEvent match {
      case EmptyZoneEvent =>
        state

      case zoneCreatedEvent: ZoneCreatedEvent =>
        state.copy(
          zone = Some(zoneCreatedEvent.zone),
          balances = state.balances ++ zoneCreatedEvent.zone.accounts.values
            .map(_.id -> BigDecimal(0))
            .toMap
        )

      case ClientJoinedEvent(maybeActorRefString) =>
        Semigroupal[Option].product(event.publicKey, maybeActorRefString) match {
          case None =>
            state

          case Some((publicKey, actorRefString)) =>
            val actorRef = resolver.resolveActorRef(actorRefString)
            clientConnectionWatcher ! ClientConnectionWatcherActor.Watch(actorRef)
            notificationSequenceNumbers += actorRef -> 0
            if (state.connectedClients.isEmpty) passivationCountdown ! PassivationCountdownActor.Stop
            val updatedClientConnections = state.connectedClients + (actorRef -> publicKey)
            state.copy(
              connectedClients = updatedClientConnections
            )
        }

      case ClientQuitEvent(maybeActorRefString) =>
        maybeActorRefString match {
          case None =>
            state

          case Some(actorRefString) =>
            val actorRef = resolver.resolveActorRef(actorRefString)
            clientConnectionWatcher ! ClientConnectionWatcherActor.Unwatch(actorRef)
            notificationSequenceNumbers -= actorRef
            val updatedClientConnections = state.connectedClients - actorRef
            if (updatedClientConnections.isEmpty) passivationCountdown ! PassivationCountdownActor.Start
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
              transactions = state.zone
                .map(_.transactions)
                .getOrElse(Map.empty) + (transaction.id -> transaction)
            ))
        )
    }

}
