package com.dhpcs.liquidity.actor.protocol

import cats.data.ValidatedNel
import com.dhpcs.liquidity.model._

sealed abstract class ZoneCommand
case object EmptyZoneCommand extends ZoneCommand
final case class CreateZoneCommand(equityOwnerPublicKey: PublicKey,
                                   equityOwnerName: Option[String],
                                   equityOwnerMetadata: Option[com.google.protobuf.struct.Struct],
                                   equityAccountName: Option[String],
                                   equityAccountMetadata: Option[com.google.protobuf.struct.Struct],
                                   name: Option[String] = None,
                                   metadata: Option[com.google.protobuf.struct.Struct] = None)
    extends ZoneCommand
case object JoinZoneCommand                                  extends ZoneCommand
case object QuitZoneCommand                                  extends ZoneCommand
final case class ChangeZoneNameCommand(name: Option[String]) extends ZoneCommand
final case class CreateMemberCommand(ownerPublicKey: PublicKey,
                                     name: Option[String] = None,
                                     metadata: Option[com.google.protobuf.struct.Struct] = None)
    extends ZoneCommand
final case class UpdateMemberCommand(member: Member) extends ZoneCommand
final case class CreateAccountCommand(ownerMemberIds: Set[MemberId],
                                      name: Option[String] = None,
                                      metadata: Option[com.google.protobuf.struct.Struct] = None)
    extends ZoneCommand
final case class UpdateAccountCommand(account: Account) extends ZoneCommand
final case class AddTransactionCommand(actingAs: MemberId,
                                       from: AccountId,
                                       to: AccountId,
                                       value: BigDecimal,
                                       description: Option[String] = None,
                                       metadata: Option[com.google.protobuf.struct.Struct] = None)
    extends ZoneCommand

object ZoneResponse {
  final case class Error(code: Int, description: String)
}

sealed abstract class ZoneResponse
case object EmptyZoneResponse                                                                       extends ZoneResponse
final case class CreateZoneResponse(result: ValidatedNel[ZoneResponse.Error, (Zone)])               extends ZoneResponse
final case class JoinZoneResponse(result: ValidatedNel[ZoneResponse.Error, (Zone, Set[PublicKey])]) extends ZoneResponse
final case class QuitZoneResponse(result: ValidatedNel[ZoneResponse.Error, Unit])                   extends ZoneResponse
final case class ChangeZoneNameResponse(result: ValidatedNel[ZoneResponse.Error, Unit])             extends ZoneResponse
final case class CreateMemberResponse(result: ValidatedNel[ZoneResponse.Error, (Member)])           extends ZoneResponse
final case class UpdateMemberResponse(result: ValidatedNel[ZoneResponse.Error, Unit])               extends ZoneResponse
final case class CreateAccountResponse(result: ValidatedNel[ZoneResponse.Error, (Account)])         extends ZoneResponse
final case class UpdateAccountResponse(result: ValidatedNel[ZoneResponse.Error, Unit])              extends ZoneResponse
final case class AddTransactionResponse(result: ValidatedNel[ZoneResponse.Error, (Transaction)])    extends ZoneResponse

sealed abstract class ZoneNotification
case object EmptyZoneNotification                                       extends ZoneNotification
final case class ClientJoinedZoneNotification(publicKey: PublicKey)     extends ZoneNotification
final case class ClientQuitZoneNotification(publicKey: PublicKey)       extends ZoneNotification
case object ZoneTerminatedNotification                                  extends ZoneNotification
final case class ZoneNameChangedNotification(name: Option[String])      extends ZoneNotification
final case class MemberCreatedNotification(member: Member)              extends ZoneNotification
final case class MemberUpdatedNotification(member: Member)              extends ZoneNotification
final case class AccountCreatedNotification(account: Account)           extends ZoneNotification
final case class AccountUpdatedNotification(account: Account)           extends ZoneNotification
final case class TransactionAddedNotification(transaction: Transaction) extends ZoneNotification

sealed abstract class ZoneValidatorMessage extends Serializable

final case class ZoneCommandEnvelope(zoneId: ZoneId,
                                     zoneCommand: ZoneCommand,
                                     publicKey: PublicKey,
                                     correlationId: Long,
                                     sequenceNumber: Long,
                                     deliveryId: Long)
    extends ZoneValidatorMessage

final case class ZoneCommandReceivedConfirmation(zoneId: ZoneId, deliveryId: Long) extends ZoneValidatorMessage

final case class ZoneRestarted(zoneId: ZoneId) extends ZoneValidatorMessage

final case class ZoneResponseEnvelope(zoneResponse: ZoneResponse,
                                      correlationId: Long,
                                      sequenceNumber: Long,
                                      deliveryId: Long)
    extends ZoneValidatorMessage

final case class ZoneNotificationEnvelope(zoneId: ZoneId,
                                          zoneNotification: ZoneNotification,
                                          sequenceNumber: Long,
                                          deliveryId: Long)
    extends ZoneValidatorMessage

final case class ActiveZoneSummary(zoneId: ZoneId,
                                   metadata: Option[com.google.protobuf.struct.Struct],
                                   members: Set[Member],
                                   accounts: Set[Account],
                                   transactions: Set[Transaction],
                                   clientConnections: Set[PublicKey])
    extends ZoneValidatorMessage
