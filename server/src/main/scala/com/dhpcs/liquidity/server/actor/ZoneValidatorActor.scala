package com.dhpcs.liquidity.server.actor

import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.{InvalidKeySpecException, X509EncodedKeySpec}
import java.time.Instant

import akka.actor.typed._
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Publish
import akka.cluster.sharding.typed.ShardingMessageExtractor
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.scaladsl._
import cats.Semigroupal
import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.instances.option._
import cats.instances.set._
import cats.syntax.apply._
import cats.syntax.validated._
import com.dhpcs.liquidity.actor.protocol.clientconnection._
import com.dhpcs.liquidity.actor.protocol.liquidityserver.ZoneResponseEnvelope
import com.dhpcs.liquidity.actor.protocol.zonemonitor._
import com.dhpcs.liquidity.actor.protocol.zonevalidator._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.persistence.EventTags
import com.dhpcs.liquidity.persistence.zone._
import com.dhpcs.liquidity.ws.protocol._

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object ZoneValidatorActor {

  final val ShardingTypeName =
    EntityTypeKey[SerializableZoneValidatorMessage]("zoneValidator")

  private[this] final val MaxNumberOfShards = 10

  val messageExtractor
    : ShardingMessageExtractor[SerializableZoneValidatorMessage,
                               SerializableZoneValidatorMessage] =
    ShardingMessageExtractor.noEnvelope[SerializableZoneValidatorMessage](
      MaxNumberOfShards,
      handOffStopMessage = StopZone) {
      // This has to be part of SerializableZoneValidatorMessage because the
      // akka-typed sharding API requires that the hand-off stop-message is a
      // subtype of the ShardingMessageExtractor Envelope type. Of course,
      // hand-off stop-messages are sent directly to the entity to be
      // stopped, so this extractor won't actually encounter them.
      case StopZone =>
        throw new IllegalArgumentException("Received StopZone")

      case GetZoneStateCommand(_, zoneId) =>
        zoneId.value

      case ZoneCommandEnvelope(_, zoneId, _, _, _, _) =>
        zoneId.value

      case ZoneNotificationSubscription(_, zoneId, _, _) =>
        zoneId.value
    }

  private[this] final val PassivationTimeout = 2.minutes

  def shardingBehavior(
      entityId: String): Behavior[SerializableZoneValidatorMessage] =
    Behaviors
      .setup[ZoneValidatorMessage] { context =>
        context.log.info("Starting")
        val mediator = DistributedPubSub(context.system.toUntyped).mediator
        val notificationSequenceNumbers =
          mutable.Map.empty[ActorRef[Nothing], Long]
        implicit val resolver: ActorRefResolver =
          ActorRefResolver(context.system)
        persistentBehavior(
          ZoneId.fromPersistenceId(entityId),
          notificationSequenceNumbers,
          mediator,
          context
        )
      }
      .narrow[SerializableZoneValidatorMessage]

  private[this] final val SnapShotInterval = 100

  private[this] def persistentBehavior(
      id: ZoneId,
      notificationSequenceNumbers: mutable.Map[ActorRef[Nothing], Long],
      mediator: ActorRef[Publish],
      context: ActorContext[ZoneValidatorMessage]
  )(implicit resolver: ActorRefResolver): Behavior[ZoneValidatorMessage] =
    PersistentBehaviors
      .receive[ZoneValidatorMessage, ZoneEventEnvelope, ZoneState](
        persistenceId = id.persistenceId,
        initialState = ZoneState(zone = None,
                                 balances = Map.empty,
                                 connectedClients = Map.empty),
        commandHandler = (context, state, command) =>
          command match {
            case StopZone =>
              context.log.info("Stopping")
              Effect.stop

            case GetZoneStateCommand(replyTo, _) =>
              replyTo ! state
              Effect.none

            case zoneCommandEnvelope: ZoneCommandEnvelope =>
              handleCommand(mediator,
                            context,
                            id,
                            notificationSequenceNumbers,
                            state,
                            zoneCommandEnvelope)

            case ZoneNotificationSubscription(subscriber,
                                              _,
                                              remoteAddress,
                                              publicKey) =>
              state.zone match {
                case None =>
                  subscriber.upcast ! ZoneNotificationEnvelope(
                    context.self,
                    id,
                    sequenceNumber = 0,
                    ZoneStateNotification(zone = None,
                                          connectedClients = Map.empty)
                  )
                  Effect.none
                case Some(zone) =>
                  def connectedClients(state: ZoneState) =
                    state.connectedClients.map {
                      case (clientConnection, _publicKey) =>
                        resolver
                          .toSerializationFormat(clientConnection) -> _publicKey
                    }
                  if (state.connectedClients.contains(subscriber.upcast)) {
                    // We already accepted the command; this was just a redelivery
                    notificationSequenceNumbers += subscriber.upcast -> 0
                    deliverNotification(
                      context.self,
                      id,
                      Iterable(subscriber.upcast),
                      notificationSequenceNumbers,
                      ZoneStateNotification(Some(zone), connectedClients(state))
                    )
                    Effect.none
                  } else
                    Effect
                      .persist(
                        ZoneEventEnvelope(
                          Some(remoteAddress),
                          Some(publicKey),
                          timestamp = Instant.now(),
                          ClientJoinedEvent(
                            Some(resolver.toSerializationFormat(subscriber)))
                        )
                      )
                      .andThen {
                        state =>
                          deliverNotification(
                            context.self,
                            id,
                            Iterable(subscriber.upcast),
                            notificationSequenceNumbers,
                            ZoneStateNotification(Some(zone),
                                                  connectedClients(state))
                          )
                          deliverNotification(
                            context.self,
                            id,
                            state.connectedClients.keys,
                            notificationSequenceNumbers,
                            ClientJoinedNotification(
                              ActorRefResolver(context.system)
                                .toSerializationFormat(subscriber),
                              publicKey
                            )
                          )
                      }
              }

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
                        ClientQuitEvent(Some(
                          resolver.toSerializationFormat(clientConnection)))
                      )
                    )
                    .andThen(state =>
                      deliverNotification(
                        context.self,
                        id,
                        state.connectedClients.keys,
                        notificationSequenceNumbers,
                        ClientQuitNotification(
                          ActorRefResolver(context.system)
                            .toSerializationFormat(clientConnection),
                          publicKey
                        )
                    ))
              }
        },
        eventHandler(notificationSequenceNumbers, context)
      )
      .snapshotEvery(SnapShotInterval)
      .withTagger(_ => Set(EventTags.ZoneEventTag))

  private[this] def handleCommand(
      mediator: ActorRef[Publish],
      context: ActorContext[ZoneValidatorMessage],
      id: ZoneId,
      notificationSequenceNumbers: mutable.Map[ActorRef[Nothing], Long],
      state: ZoneState,
      zoneCommandEnvelope: ZoneCommandEnvelope)
    : Effect[ZoneEventEnvelope, ZoneState] =
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
        val validatedEquityOwnerPublicKey = validatePublicKey(
          equityOwnerPublicKey)
        val validatedEquityOwnerName = validateTag(equityOwnerName)
        val validatedParams = {
          val validatedEquityOwnerMetadata = validateMetadata(
            equityOwnerMetadata)
          val validatedEquityAccountName = validateTag(equityAccountName)
          val validatedEquityAccountMetadata = validateMetadata(
            equityAccountMetadata)
          val validatedName = validateTag(name)
          val validatedMetadata = validateMetadata(metadata)
          (validatedEquityOwnerPublicKey,
           validatedEquityOwnerName,
           validatedEquityOwnerMetadata,
           validatedEquityAccountName,
           validatedEquityAccountMetadata,
           validatedName,
           validatedMetadata).tupled
        }
        validatedParams match {
          case Invalid(errors) =>
            deliverResponse(
              zoneCommandEnvelope.replyTo,
              CreateZoneResponse(Validated.invalid(errors))
            )
            Effect.none
          case Valid(
              (validEquityOwnerPublicKey,
               validEquityOwnerName,
               validEquityOwnerMetadata,
               validEquityAccountName,
               vaildEquityAccountMetadata,
               validName,
               validMetadata)) =>
            state.zone match {
              case Some(zone) =>
                // We already accepted the command; this was just a redelivery
                deliverResponse(
                  zoneCommandEnvelope.replyTo,
                  CreateZoneResponse(zone.valid)
                )
                Effect.none
              case None =>
                val equityOwner = Member(
                  MemberId(0.toString),
                  Set(validEquityOwnerPublicKey),
                  validEquityOwnerName,
                  validEquityOwnerMetadata
                )
                val equityAccount = Account(
                  AccountId(0.toString),
                  Set(equityOwner.id),
                  validEquityAccountName,
                  vaildEquityAccountMetadata
                )
                val created = Instant.now().toEpochMilli
                val expires = created + ZoneLifetime.toMillis
                val zone = Zone(
                  id,
                  equityAccount.id,
                  members = Map(equityOwner.id -> equityOwner),
                  accounts = Map(equityAccount.id -> equityAccount),
                  transactions = Map.empty,
                  created,
                  expires,
                  validName,
                  validMetadata
                )
                acceptCommand(
                  context.self,
                  mediator,
                  id,
                  notificationSequenceNumbers,
                  zoneCommandEnvelope,
                  ZoneCreatedEvent(zone)
                )
            }
        }

      case ChangeZoneNameCommand(name) =>
        state.zone match {
          case None =>
            deliverResponse(
              zoneCommandEnvelope.replyTo,
              ChangeZoneNameResponse(
                Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist))
            )
            Effect.none
          case Some(zone) =>
            val validatedParams = validateTag(name)
            validatedParams match {
              case Invalid(errors) =>
                deliverResponse(
                  zoneCommandEnvelope.replyTo,
                  ChangeZoneNameResponse(
                    Validated.invalid(errors)
                  )
                )
                Effect.none
              case Valid(_) =>
                if (zone.name == name) {
                  // We probably already accepted the command and this was just
                  // a redelivery. In any case, we don't need to persist
                  // anything.
                  deliverResponse(
                    zoneCommandEnvelope.replyTo,
                    ChangeZoneNameResponse(().valid)
                  )
                  Effect.none
                } else
                  acceptCommand(
                    context.self,
                    mediator,
                    id,
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
              zoneCommandEnvelope.replyTo,
              CreateMemberResponse(
                Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist))
            )
            Effect.none
          case Some(zone) =>
            val validatedParams = {
              val validatedMemberId =
                MemberId(zone.members.size.toString)
                  .valid[NonEmptyList[ZoneResponse.Error]]
              val validatedOwnerPublicKeys = validatePublicKeys(ownerPublicKeys)
              val validatedName = validateTag(name)
              val validatedMetadata = validateMetadata(metadata)
              (validatedMemberId,
               validatedOwnerPublicKeys,
               validatedName,
               validatedMetadata).tupled
            }
            validatedParams match {
              case Invalid(errors) =>
                deliverResponse(
                  zoneCommandEnvelope.replyTo,
                  CreateMemberResponse(
                    Validated.invalid(errors)
                  )
                )
                Effect.none
              case Valid(params) =>
                acceptCommand(
                  context.self,
                  mediator,
                  id,
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
              zoneCommandEnvelope.replyTo,
              UpdateMemberResponse(
                Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist))
            )
            Effect.none
          case Some(zone) =>
            val validatedParams =
              validateCanUpdateMember(zone,
                                      member.id,
                                      zoneCommandEnvelope.publicKey).andThen {
                _ =>
                  val validatedOwnerPublicKeys =
                    validatePublicKeys(member.ownerPublicKeys)
                  val validatedTag = validateTag(member.name)
                  val validatedMetadata = validateMetadata(member.metadata)
                  (validatedOwnerPublicKeys, validatedTag, validatedMetadata).tupled
              }
            validatedParams match {
              case Invalid(errors) =>
                deliverResponse(
                  zoneCommandEnvelope.replyTo,
                  UpdateMemberResponse(
                    Validated.invalid(errors)
                  )
                )
                Effect.none
              case Valid(_) =>
                if (member == zone.members(member.id)) {
                  // We probably already accepted the command and this was just
                  // a redelivery. In any case, we don't need to persist
                  // anything.
                  deliverResponse(
                    zoneCommandEnvelope.replyTo,
                    UpdateMemberResponse(().valid)
                  )
                  Effect.none
                } else
                  acceptCommand(
                    context.self,
                    mediator,
                    id,
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
              zoneCommandEnvelope.replyTo,
              CreateAccountResponse(
                Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist))
            )
            Effect.none
          case Some(zone) =>
            val validatedParams = {
              val validatedAccountId =
                AccountId(zone.accounts.size.toString)
                  .valid[NonEmptyList[ZoneResponse.Error]]
              val validatedOwnerMemberIds = validateMemberIds(zone, owners)
              val validatedTag = validateTag(name)
              val validatedMetadata = validateMetadata(metadata)
              (validatedAccountId,
               validatedOwnerMemberIds,
               validatedTag,
               validatedMetadata).tupled
            }
            validatedParams match {
              case Invalid(errors) =>
                deliverResponse(
                  zoneCommandEnvelope.replyTo,
                  CreateAccountResponse(
                    Validated.invalid(errors)
                  )
                )
                Effect.none
              case Valid(params) =>
                acceptCommand(
                  context.self,
                  mediator,
                  id,
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
              zoneCommandEnvelope.replyTo,
              UpdateAccountResponse(
                Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist))
            )
            Effect.none
          case Some(zone) =>
            val validatedParams =
              validateCanUpdateAccount(zone,
                                       zoneCommandEnvelope.publicKey,
                                       actingAs,
                                       account.id).andThen { _ =>
                val validatedOwnerMemberIds =
                  validateMemberIds(zone, account.ownerMemberIds)
                val validatedTag = validateTag(account.name)
                val validatedMetadata = validateMetadata(account.metadata)
                (validatedOwnerMemberIds, validatedTag, validatedMetadata).tupled
              }
            validatedParams match {
              case Invalid(errors) =>
                deliverResponse(
                  zoneCommandEnvelope.replyTo,
                  UpdateAccountResponse(
                    Validated.invalid(errors)
                  )
                )
                Effect.none
              case Valid(_) =>
                if (account == zone.accounts(account.id)) {
                  // We probably already accepted the command and this was just
                  // a redelivery. In any case, we don't need to persist
                  // anything.
                  deliverResponse(
                    zoneCommandEnvelope.replyTo,
                    UpdateAccountResponse(().valid)
                  )
                  Effect.none
                } else
                  acceptCommand(
                    context.self,
                    mediator,
                    id,
                    notificationSequenceNumbers,
                    zoneCommandEnvelope,
                    AccountUpdatedEvent(Some(actingAs), account)
                  )
            }
        }

      case AddTransactionCommand(actingAs,
                                 from,
                                 to,
                                 value,
                                 description,
                                 metadata) =>
        state.zone match {
          case None =>
            deliverResponse(
              zoneCommandEnvelope.replyTo,
              AddTransactionResponse(
                Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist))
            )
            Effect.none
          case Some(zone) =>
            val validatedParams = {
              val validatedTransactionId =
                TransactionId(zone.transactions.size.toString)
                  .valid[NonEmptyList[ZoneResponse.Error]]
              val validatedFromValueAndActingAs =
                validateFromAccount(zone, actingAs, from).andThen { _ =>
                  val validatedValue =
                    validateTransactionValue(from, value, zone, state.balances)
                  val validatedActingAs =
                    validateActingAs(zone,
                                     zoneCommandEnvelope.publicKey,
                                     actingAs)
                  (validatedValue, validatedActingAs).tupled
                }
              val validatedTo = validateToAccount(zone, from, to)
              val validatedCreated = System
                .currentTimeMillis()
                .valid[NonEmptyList[ZoneResponse.Error]]
              val validatedDescription = validateTag(description)
              val validatedMetadata = validateMetadata(metadata)
              (validatedTransactionId,
               validatedFromValueAndActingAs,
               validatedTo,
               validatedCreated,
               validatedDescription,
               validatedMetadata).tupled.map {
                case (transactionId, _, _, created, _, _) =>
                  (transactionId,
                   from,
                   to,
                   value,
                   actingAs,
                   created,
                   description,
                   metadata)
              }
            }
            validatedParams match {
              case Invalid(errors) =>
                deliverResponse(
                  zoneCommandEnvelope.replyTo,
                  AddTransactionResponse(Validated.invalid(errors))
                )
                Effect.none
              case Valid(params) =>
                acceptCommand(
                  context.self,
                  mediator,
                  id,
                  notificationSequenceNumbers,
                  zoneCommandEnvelope,
                  TransactionAddedEvent(Transaction.tupled(params))
                )
            }
        }
    }

  private[this] final val ZoneLifetime = java.time.Duration.ofDays(30)

  private[this] def validatePublicKey(
      publicKey: PublicKey): ValidatedNel[ZoneResponse.Error, PublicKey] =
    Try(
      KeyFactory
        .getInstance("RSA")
        .generatePublic(new X509EncodedKeySpec(publicKey.value.toByteArray))
        .asInstanceOf[RSAPublicKey]) match {
      case Failure(_: InvalidKeySpecException) =>
        Validated.invalidNel(ZoneResponse.Error.invalidPublicKeyType)
      case Failure(_) =>
        Validated.invalidNel(ZoneResponse.Error.invalidPublicKey)
      case Success(value)
          if value.getModulus.bitLength() != ZoneCommand.RequiredKeySize =>
        Validated.invalidNel(ZoneResponse.Error.invalidPublicKeyLength)
      case Success(_) =>
        Validated.valid(publicKey)
    }

  private[this] def validatePublicKeys(publicKeys: Set[PublicKey])
    : ValidatedNel[ZoneResponse.Error, Set[PublicKey]] = {
    if (publicKeys.isEmpty)
      Validated.invalidNel(ZoneResponse.Error.noPublicKeys)
    else
      publicKeys
        .map(validatePublicKey)
        .foldLeft(Set.empty[PublicKey].valid[NonEmptyList[ZoneResponse.Error]])(
          (validatedPublicKeys, validatedPublicKey) =>
            validatedPublicKeys.combine(validatedPublicKey.map(Set(_))))
  }

  private[this] def validateMemberIds(zone: Zone, memberIds: Set[MemberId])
    : ValidatedNel[ZoneResponse.Error, Set[MemberId]] = {
    def validateMemberId(
        memberId: MemberId): ValidatedNel[ZoneResponse.Error, MemberId] =
      if (!zone.members.contains(memberId))
        Validated.invalidNel(ZoneResponse.Error.memberDoesNotExist(memberId))
      else
        Validated.valid(memberId)
    if (memberIds.isEmpty) Validated.invalidNel(ZoneResponse.Error.noMemberIds)
    else
      memberIds
        .map(validateMemberId)
        .foldLeft(Set.empty[MemberId].valid[NonEmptyList[ZoneResponse.Error]])(
          (validatedMemberIds, validatedMemberId) =>
            validatedMemberIds.combine(validatedMemberId.map(Set(_))))
  }

  private[this] def validateCanUpdateMember(
      zone: Zone,
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

  private[this] def validateCanUpdateAccount(
      zone: Zone,
      publicKey: PublicKey,
      actingAs: MemberId,
      accountId: AccountId): ValidatedNel[ZoneResponse.Error, Unit] =
    zone.accounts.get(accountId) match {
      case None =>
        Validated.invalidNel(ZoneResponse.Error.accountDoesNotExist)
      case Some(account) if !account.ownerMemberIds.contains(actingAs) =>
        Validated.invalidNel(ZoneResponse.Error.accountOwnerMismatch)
      case _ =>
        zone.members(actingAs) match {
          case member if !member.ownerPublicKeys.contains(publicKey) =>
            Validated.invalidNel(ZoneResponse.Error.memberKeyMismatch)
          case _ =>
            Validated.valid(())
        }
    }

  private[this] def validateFromAccount(
      zone: Zone,
      actingAs: MemberId,
      accountId: AccountId): ValidatedNel[ZoneResponse.Error, AccountId] =
    zone.accounts.get(accountId) match {
      case None =>
        Validated.invalidNel(ZoneResponse.Error.sourceAccountDoesNotExist)
      case Some(account) if !account.ownerMemberIds.contains(actingAs) =>
        Validated.invalidNel(ZoneResponse.Error.accountOwnerMismatch)
      case _ =>
        Validated.valid(accountId)
    }

  private[this] def validateToAccount(
      zone: Zone,
      from: AccountId,
      accountId: AccountId): ValidatedNel[ZoneResponse.Error, AccountId] =
    zone.accounts.get(accountId) match {
      case None =>
        Validated.invalidNel(ZoneResponse.Error.destinationAccountDoesNotExist)
      case _ if accountId == from =>
        Validated.invalidNel(ZoneResponse.Error.reflexiveTransaction)
      case _ =>
        Validated.valid(accountId)
    }

  private[this] def validateActingAs(
      zone: Zone,
      publicKey: PublicKey,
      actingAs: MemberId): ValidatedNel[ZoneResponse.Error, MemberId] =
    zone.members(actingAs) match {
      case member if !member.ownerPublicKeys.contains(publicKey) =>
        Validated.invalidNel(ZoneResponse.Error.memberKeyMismatch)
      case _ =>
        Validated.valid(actingAs)
    }

  private[this] def validateTransactionValue(
      from: AccountId,
      value: BigDecimal,
      zone: Zone,
      balances: Map[AccountId, BigDecimal])
    : ValidatedNel[ZoneResponse.Error, BigDecimal] =
    (if (value.compare(0) == -1)
       Validated.invalidNel(ZoneResponse.Error.negativeTransactionValue)
     else Validated.valid(value)).andThen(
      value =>
        if (balances(from) - value < 0 && from != zone.equityAccountId)
          Validated.invalidNel(ZoneResponse.Error.insufficientBalance)
        else
          Validated.valid(value)
    )

  private[this] def validateTag(
      tag: Option[String]): ValidatedNel[ZoneResponse.Error, Option[String]] =
    tag.map(_.length) match {
      case Some(tagLength) if tagLength > ZoneCommand.MaximumTagLength =>
        Validated.invalidNel(ZoneResponse.Error.tagLengthExceeded)
      case _ =>
        Validated.valid(tag)
    }

  private[this] def validateMetadata(
      metadata: Option[com.google.protobuf.struct.Struct])
    : ValidatedNel[ZoneResponse.Error,
                   Option[com.google.protobuf.struct.Struct]] =
    metadata.map(_.toByteArray.length) match {
      case Some(metadataSize)
          if metadataSize > ZoneCommand.MaximumMetadataSize =>
        Validated.invalidNel(ZoneResponse.Error.metadataLengthExceeded)
      case _ =>
        Validated.valid(metadata)
    }

  private[this] def acceptCommand(
      self: ActorRef[ZoneValidatorMessage],
      mediator: ActorRef[Publish],
      id: ZoneId,
      notificationSequenceNumbers: mutable.Map[ActorRef[Nothing], Long],
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
      .andThen { state =>
        (event match {
          case EmptyZoneEvent =>
            None

          case ZoneCreatedEvent(zone) =>
            Some(CreateZoneResponse(Validated.valid(zone)))

          case ClientJoinedEvent(_) =>
            None

          case ClientQuitEvent(_) =>
            None

          case ZoneNameChangedEvent(_) =>
            Some(ChangeZoneNameResponse(Validated.valid(())))

          case MemberCreatedEvent(member) =>
            Some(CreateMemberResponse(Validated.valid(member)))

          case MemberUpdatedEvent(_) =>
            Some(UpdateMemberResponse(Validated.valid(())))

          case AccountCreatedEvent(account) =>
            Some(CreateAccountResponse(Validated.valid(account)))

          case AccountUpdatedEvent(_, _) =>
            Some(UpdateAccountResponse(Validated.valid(())))

          case TransactionAddedEvent(transaction) =>
            Some(AddTransactionResponse(Validated.valid(transaction)))
        }).foreach(
          deliverResponse(
            zoneCommandEnvelope.replyTo,
            _
          )
        )
        (event match {
          case EmptyZoneEvent =>
            None

          case ZoneCreatedEvent(_) =>
            None

          case ClientJoinedEvent(maybeActorRefString) =>
            maybeActorRefString.map(actorRefString =>
              ClientJoinedNotification(connectionId = actorRefString,
                                       zoneCommandEnvelope.publicKey))

          case ClientQuitEvent(maybeActorRefString) =>
            maybeActorRefString.map(actorRefString =>
              ClientQuitNotification(connectionId = actorRefString,
                                     zoneCommandEnvelope.publicKey))

          case ZoneNameChangedEvent(name) =>
            Some(ZoneNameChangedNotification(name))

          case MemberCreatedEvent(member) =>
            Some(MemberCreatedNotification(member))

          case MemberUpdatedEvent(member) =>
            Some(MemberUpdatedNotification(member))

          case AccountCreatedEvent(account) =>
            Some(AccountCreatedNotification(account))

          case AccountUpdatedEvent(None, account) =>
            Some(
              AccountUpdatedNotification(account.ownerMemberIds.head, account))

          case AccountUpdatedEvent(Some(actingAs), account) =>
            Some(AccountUpdatedNotification(actingAs, account))

          case TransactionAddedEvent(transaction) =>
            Some(TransactionAddedNotification(transaction))
        }).foreach(
          deliverNotification(
            self,
            id,
            state.connectedClients.keys,
            notificationSequenceNumbers,
            _
          )
        )
        state.zone.foreach(
          zone =>
            mediator ! Publish(
              ZoneMonitorActor.ZoneStatusTopic,
              UpsertActiveZoneSummary(
                self,
                ActiveZoneSummary(
                  id,
                  zone.members.size,
                  zone.accounts.size,
                  zone.transactions.size,
                  zone.metadata,
                  state.connectedClients.values.toSet
                )
              )
          ))
      }

  private[this] def deliverResponse(
      clientConnection: ActorRef[ZoneResponseEnvelope],
      response: ZoneResponse): Unit =
    clientConnection ! ZoneResponseEnvelope(response)

  private[this] def deliverNotification(
      self: ActorRef[ZoneValidatorMessage],
      id: ZoneId,
      clientConnections: Iterable[
        ActorRef[SerializableClientConnectionMessage]],
      notificationSequenceNumbers: mutable.Map[ActorRef[Nothing], Long],
      notification: ZoneNotification): Unit =
    clientConnections.foreach { clientConnection =>
      val sequenceNumber = notificationSequenceNumbers(clientConnection)
      clientConnection ! ZoneNotificationEnvelope(self,
                                                  id,
                                                  sequenceNumber,
                                                  notification)
      val nextSequenceNumber = sequenceNumber + 1
      notificationSequenceNumbers += clientConnection -> nextSequenceNumber
    }

  private[this] def eventHandler(
      notificationSequenceNumbers: mutable.Map[ActorRef[Nothing], Long],
      context: ActorContext[ZoneValidatorMessage])(state: ZoneState,
                                                   event: ZoneEventEnvelope)(
      implicit resolver: ActorRefResolver): ZoneState =
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
        Semigroupal[Option]
          .product(event.publicKey, maybeActorRefString) match {
          case None =>
            state

          case Some((publicKey, actorRefString)) =>
            val actorRef = resolver.resolveActorRef(actorRefString)
            context.watchWith(actorRef, RemoveClient(actorRef))
            notificationSequenceNumbers += actorRef -> 0
            if (state.connectedClients.isEmpty)
              context.cancelReceiveTimeout()
            val updatedClientConnections = state.connectedClients +
              (actorRef -> publicKey)
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
            context.unwatch(actorRef)
            notificationSequenceNumbers -= actorRef
            val updatedClientConnections = state.connectedClients - actorRef
            if (updatedClientConnections.isEmpty)
              context.setReceiveTimeout(PassivationTimeout, StopZone)
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
              members = state.zone
                .map(_.members)
                .getOrElse(Map.empty) + (member.id -> member)
            ))
        )

      case MemberUpdatedEvent(member) =>
        state.copy(
          zone = state.zone.map(
            _.copy(
              members = state.zone
                .map(_.members)
                .getOrElse(Map.empty) + (member.id -> member)
            ))
        )

      case AccountCreatedEvent(account) =>
        state.copy(
          zone = state.zone.map(
            _.copy(
              accounts = state.zone
                .map(_.accounts)
                .getOrElse(Map.empty) + (account.id -> account)
            )),
          balances = state.balances + (account.id -> BigDecimal(0))
        )

      case AccountUpdatedEvent(_, account) =>
        state.copy(
          zone = state.zone.map(
            _.copy(
              accounts = state.zone
                .map(_.accounts)
                .getOrElse(Map.empty) + (account.id -> account)
            ))
        )

      case TransactionAddedEvent(transaction) =>
        state.copy(
          balances = state.balances +
            (transaction.from -> (state
              .balances(transaction.from) - transaction.value)) +
            (transaction.to -> (state
              .balances(transaction.to) + transaction.value)),
          zone = state.zone.map(
            _.copy(
              transactions = state.zone
                .map(_.transactions)
                .getOrElse(Map.empty) + (transaction.id -> transaction)
            ))
        )
    }

}
