package com.dhpcs.liquidity.actor.protocol.zonevalidator

import cats.data.ValidatedNel
import com.dhpcs.liquidity.model._

sealed abstract class ZoneValidatorMessage extends Serializable

final case class GetZoneStateCommand(zoneId: ZoneId)    extends ZoneValidatorMessage
final case class GetZoneStateResponse(state: ZoneState) extends ZoneValidatorMessage

object ZoneCommand {

  final val RequiredKeySize     = 2048
  final val MaximumTagLength    = 160
  final val MaximumMetadataSize = 1024

}

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
final case class CreateMemberCommand(ownerPublicKeys: Set[PublicKey],
                                     name: Option[String] = None,
                                     metadata: Option[com.google.protobuf.struct.Struct] = None)
    extends ZoneCommand
final case class UpdateMemberCommand(member: Member) extends ZoneCommand
final case class CreateAccountCommand(ownerMemberIds: Set[MemberId],
                                      name: Option[String] = None,
                                      metadata: Option[com.google.protobuf.struct.Struct] = None)
    extends ZoneCommand
final case class UpdateAccountCommand(actingAs: MemberId, account: Account) extends ZoneCommand
final case class AddTransactionCommand(actingAs: MemberId,
                                       from: AccountId,
                                       to: AccountId,
                                       value: BigDecimal,
                                       description: Option[String] = None,
                                       metadata: Option[com.google.protobuf.struct.Struct] = None)
    extends ZoneCommand

object ZoneResponse {

  object Error {
    val zoneDoesNotExist  = ZoneResponse.Error(code = 0, description = "Zone must be created.")
    val zoneAlreadyExists = ZoneResponse.Error(code = 1, description = "Zone must not already exist.")
    val zoneNotJoined     = ZoneResponse.Error(code = 2, description = "Zone must have been joined.")
    val zoneAlreadyJoined = ZoneResponse.Error(code = 3, description = "Zone must not already have been joined.")
    val noPublicKeys =
      ZoneResponse.Error(code = 4, description = "At least one public key must be specified.")
    val invalidPublicKeyType =
      ZoneResponse.Error(code = 5, description = "Public key type must be RSA.")
    val invalidPublicKey =
      ZoneResponse.Error(code = 6, description = "Public key must be valid.")
    val invalidPublicKeyLength =
      ZoneResponse.Error(code = 7, description = s"Public key length must be ${ZoneCommand.RequiredKeySize} bits.")
    val noMemberIds =
      ZoneResponse.Error(code = 8, description = "At least one member ID must be specified.")
    def memberDoesNotExist(memberId: MemberId) =
      ZoneResponse.Error(code = 9, description = s"Member ${memberId.id} must exist.")
    val memberDoesNotExist =
      ZoneResponse.Error(code = 10, description = "Member must exist.")
    val memberKeyMismatch =
      ZoneResponse.Error(code = 11, description = "Client public key must match that of the member.")
    val accountDoesNotExist =
      ZoneResponse.Error(code = 12, description = "Account must exist.")
    val accountOwnerKeyMismatch =
      ZoneResponse.Error(code = 13, description = "Client public key must match that of an owner.")
    val accountOwnerMismatch =
      ZoneResponse.Error(code = 14, description = "Member must be an account owner.")
    val sourceAccountDoesNotExist =
      ZoneResponse.Error(code = 15, description = "Source account must exist.")
    val destinationAccountDoesNotExist =
      ZoneResponse.Error(code = 16, description = "Destination account must exist.")
    val reflexiveTransaction =
      ZoneResponse.Error(code = 17, description = "Destination account must not also be the source account.")
    val negativeTransactionValue =
      ZoneResponse.Error(code = 18, description = "Transaction value must be positive.")
    val insufficientBalance =
      ZoneResponse.Error(code = 19,
                         description =
                           "Source account must have a balance greater than or equal to the transaction value.")
    val tagLengthExceeded =
      ZoneResponse.Error(code = 20,
                         description = s"Tag length must be less than ${ZoneCommand.MaximumTagLength} characters.")
    val metadataLengthExceeded =
      ZoneResponse.Error(code = 21,
                         description = s"Metadata size must be less than ${ZoneCommand.MaximumMetadataSize} bytes.")
  }

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
final case class ZoneNameChangedNotification(name: Option[String])      extends ZoneNotification
final case class MemberCreatedNotification(member: Member)              extends ZoneNotification
final case class MemberUpdatedNotification(member: Member)              extends ZoneNotification
final case class AccountCreatedNotification(account: Account)           extends ZoneNotification
final case class AccountUpdatedNotification(account: Account)           extends ZoneNotification
final case class TransactionAddedNotification(transaction: Transaction) extends ZoneNotification

final case class ZoneCommandEnvelope(zoneId: ZoneId,
                                     zoneCommand: ZoneCommand,
                                     // TODO: Add metadata
                                     publicKey: PublicKey,
                                     correlationId: Long,
                                     sequenceNumber: Long,
                                     deliveryId: Long)
    extends ZoneValidatorMessage

final case class ZoneCommandReceivedConfirmation(zoneId: ZoneId, deliveryId: Long) extends ZoneValidatorMessage

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
