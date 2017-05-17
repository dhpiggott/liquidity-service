package com.dhpcs.liquidity.actor.protocol

import com.dhpcs.liquidity.actor.protocol.ZoneValidatorMessage._
import com.dhpcs.liquidity.model._

object ZoneValidatorMessage {

  sealed abstract class ZoneCommand {
    def zoneId: ZoneId
  }

  case object EmptyZoneCommand extends ZoneCommand {
    override def zoneId: ZoneId = sys.error("EmptyZoneCommand")
  }
  final case class CreateZoneCommand(zoneId: ZoneId,
                                     equityOwnerPublicKey: PublicKey,
                                     equityOwnerName: Option[String],
                                     equityOwnerMetadata: Option[com.google.protobuf.struct.Struct],
                                     equityAccountName: Option[String],
                                     equityAccountMetadata: Option[com.google.protobuf.struct.Struct],
                                     name: Option[String] = None,
                                     metadata: Option[com.google.protobuf.struct.Struct] = None)
      extends ZoneCommand
  final case class JoinZoneCommand(zoneId: ZoneId)                             extends ZoneCommand
  final case class QuitZoneCommand(zoneId: ZoneId)                             extends ZoneCommand
  final case class ChangeZoneNameCommand(zoneId: ZoneId, name: Option[String]) extends ZoneCommand
  final case class CreateMemberCommand(zoneId: ZoneId,
                                       ownerPublicKey: PublicKey,
                                       name: Option[String] = None,
                                       metadata: Option[com.google.protobuf.struct.Struct] = None)
      extends ZoneCommand
  final case class UpdateMemberCommand(zoneId: ZoneId, member: Member) extends ZoneCommand
  final case class CreateAccountCommand(zoneId: ZoneId,
                                        ownerMemberIds: Set[MemberId],
                                        name: Option[String] = None,
                                        metadata: Option[com.google.protobuf.struct.Struct] = None)
      extends ZoneCommand
  final case class UpdateAccountCommand(zoneId: ZoneId, account: Account) extends ZoneCommand
  final case class AddTransactionCommand(zoneId: ZoneId,
                                         actingAs: MemberId,
                                         from: AccountId,
                                         to: AccountId,
                                         value: BigDecimal,
                                         description: Option[String] = None,
                                         metadata: Option[com.google.protobuf.struct.Struct] = None)
      extends ZoneCommand {
    require(value >= 0)
  }

  sealed abstract class ZoneResponse

  case object EmptyZoneResponse                 extends ZoneResponse
  final case class ErrorResponse(error: String) extends ZoneResponse
  sealed abstract class SuccessResponse         extends ZoneResponse

  final case class CreateZoneResponse(zone: Zone)                                 extends SuccessResponse
  final case class JoinZoneResponse(zone: Zone, connectedClients: Set[PublicKey]) extends SuccessResponse
  case object QuitZoneResponse                                                    extends SuccessResponse
  case object ChangeZoneNameResponse                                              extends SuccessResponse
  final case class CreateMemberResponse(member: Member)                           extends SuccessResponse
  case object UpdateMemberResponse                                                extends SuccessResponse
  final case class CreateAccountResponse(account: Account)                        extends SuccessResponse
  case object UpdateAccountResponse                                               extends SuccessResponse
  final case class AddTransactionResponse(transaction: Transaction)               extends SuccessResponse

  sealed abstract class ZoneNotification {
    def zoneId: ZoneId
  }

  case object EmptyZoneNotification extends ZoneNotification {
    override def zoneId: ZoneId = sys.error("EmptyZoneNotification")
  }
  final case class ClientJoinedZoneNotification(zoneId: ZoneId, publicKey: PublicKey)     extends ZoneNotification
  final case class ClientQuitZoneNotification(zoneId: ZoneId, publicKey: PublicKey)       extends ZoneNotification
  final case class ZoneTerminatedNotification(zoneId: ZoneId)                             extends ZoneNotification
  final case class ZoneNameChangedNotification(zoneId: ZoneId, name: Option[String])      extends ZoneNotification
  final case class MemberCreatedNotification(zoneId: ZoneId, member: Member)              extends ZoneNotification
  final case class MemberUpdatedNotification(zoneId: ZoneId, member: Member)              extends ZoneNotification
  final case class AccountCreatedNotification(zoneId: ZoneId, account: Account)           extends ZoneNotification
  final case class AccountUpdatedNotification(zoneId: ZoneId, account: Account)           extends ZoneNotification
  final case class TransactionAddedNotification(zoneId: ZoneId, transaction: Transaction) extends ZoneNotification

}

sealed abstract class ZoneValidatorMessage extends Serializable

final case class AuthenticatedZoneCommandWithIds(publicKey: PublicKey,
                                                 command: ZoneCommand,
                                                 correlationId: Long,
                                                 sequenceNumber: Long,
                                                 deliveryId: Long)
    extends ZoneValidatorMessage

final case class ZoneCommandReceivedConfirmation(zoneId: ZoneId, deliveryId: Long) extends ZoneValidatorMessage

final case class ZoneAlreadyExists(createZoneCommand: CreateZoneCommand,
                                   correlationId: Long,
                                   sequenceNumber: Long,
                                   deliveryId: Long)
    extends ZoneValidatorMessage

final case class ZoneRestarted(zoneId: ZoneId) extends ZoneValidatorMessage

final case class ZoneResponseWithIds(response: ZoneResponse,
                                     correlationId: Long,
                                     sequenceNumber: Long,
                                     deliveryId: Long)
    extends ZoneValidatorMessage

final case class ZoneNotificationWithIds(notification: ZoneNotification, sequenceNumber: Long, deliveryId: Long)
    extends ZoneValidatorMessage

final case class ActiveZoneSummary(zoneId: ZoneId,
                                   metadata: Option[com.google.protobuf.struct.Struct],
                                   members: Set[Member],
                                   accounts: Set[Account],
                                   transactions: Set[Transaction],
                                   clientConnections: Set[PublicKey])
    extends ZoneValidatorMessage
