package com.dhpcs.liquidity.ws.protocol

import cats.data.ValidatedNel
import com.dhpcs.liquidity.model._

sealed abstract class ZoneMessage

sealed abstract class ZoneCommand extends ZoneMessage
object ZoneCommand {
  final val RequiredKeySize = 2048
  final val MaximumTagLength = 160
  final val MaximumMetadataSize = 1024
}

case object EmptyZoneCommand extends ZoneCommand
final case class CreateZoneCommand(
    equityOwnerPublicKey: PublicKey,
    equityOwnerName: Option[String],
    equityOwnerMetadata: Option[com.google.protobuf.struct.Struct],
    equityAccountName: Option[String],
    equityAccountMetadata: Option[com.google.protobuf.struct.Struct],
    name: Option[String],
    metadata: Option[com.google.protobuf.struct.Struct])
    extends ZoneCommand
case object JoinZoneCommand extends ZoneCommand
case object QuitZoneCommand extends ZoneCommand
final case class ChangeZoneNameCommand(name: Option[String]) extends ZoneCommand
final case class CreateMemberCommand(
    ownerPublicKeys: Set[PublicKey],
    name: Option[String],
    metadata: Option[com.google.protobuf.struct.Struct])
    extends ZoneCommand
final case class UpdateMemberCommand(member: Member) extends ZoneCommand
final case class CreateAccountCommand(
    ownerMemberIds: Set[MemberId],
    name: Option[String],
    metadata: Option[com.google.protobuf.struct.Struct])
    extends ZoneCommand
final case class UpdateAccountCommand(actingAs: MemberId, account: Account)
    extends ZoneCommand
final case class AddTransactionCommand(
    actingAs: MemberId,
    from: AccountId,
    to: AccountId,
    value: BigDecimal,
    description: Option[String],
    metadata: Option[com.google.protobuf.struct.Struct])
    extends ZoneCommand

sealed abstract class ZoneResponse extends ZoneMessage
object ZoneResponse {

  object Error {
    val zoneDoesNotExist =
      ZoneResponse.Error(code = 0, description = "Zone must be created.")
    val noPublicKeys =
      ZoneResponse.Error(code = 1,
                         description =
                           "At least one public key must be specified.")
    val invalidPublicKeyType =
      ZoneResponse.Error(code = 2, description = "Public key type must be RSA.")
    val invalidPublicKey =
      ZoneResponse.Error(code = 3, description = "Public key must be valid.")
    val invalidPublicKeyLength =
      ZoneResponse.Error(
        code = 4,
        description =
          s"Public key length must be ${ZoneCommand.RequiredKeySize} bits.")
    val noMemberIds =
      ZoneResponse.Error(code = 5,
                         description =
                           "At least one member ID must be specified.")
    def memberDoesNotExist(memberId: MemberId) =
      ZoneResponse.Error(code = 6,
                         description = s"Member ${memberId.value} must exist.")
    val memberDoesNotExist =
      ZoneResponse.Error(code = 7, description = "Member must exist.")
    val memberKeyMismatch =
      ZoneResponse.Error(code = 8,
                         description =
                           "Client public key must match that of the member.")
    val accountDoesNotExist =
      ZoneResponse.Error(code = 9, description = "Account must exist.")
    val accountOwnerKeyMismatch =
      ZoneResponse.Error(code = 10,
                         description =
                           "Client public key must match that of an owner.")
    val accountOwnerMismatch =
      ZoneResponse.Error(code = 11,
                         description = "Member must be an account owner.")
    val sourceAccountDoesNotExist =
      ZoneResponse.Error(code = 12, description = "Source account must exist.")
    val destinationAccountDoesNotExist =
      ZoneResponse.Error(code = 13,
                         description = "Destination account must exist.")
    val reflexiveTransaction =
      ZoneResponse.Error(
        code = 14,
        description = "Destination account must not also be the source account.")
    val negativeTransactionValue =
      ZoneResponse.Error(code = 15,
                         description = "Transaction value must be positive.")
    val insufficientBalance =
      ZoneResponse.Error(
        code = 16,
        description =
          "Source account must have a balance greater than or equal to the transaction value.")
    val tagLengthExceeded =
      ZoneResponse.Error(
        code = 17,
        description =
          s"Tag length must be less than ${ZoneCommand.MaximumTagLength} characters.")
    val metadataLengthExceeded =
      ZoneResponse.Error(
        code = 18,
        description =
          s"Metadata size must be less than ${ZoneCommand.MaximumMetadataSize} bytes.")
  }

  final case class Error(code: Int, description: String)

}

case object EmptyZoneResponse extends ZoneResponse
final case class CreateZoneResponse(
    result: ValidatedNel[ZoneResponse.Error, Zone])
    extends ZoneResponse
final case class JoinZoneResponse(
    result: ValidatedNel[ZoneResponse.Error, (Zone, Map[String, PublicKey])])
    extends ZoneResponse
final case class QuitZoneResponse(
    result: ValidatedNel[ZoneResponse.Error, Unit])
    extends ZoneResponse
final case class ChangeZoneNameResponse(
    result: ValidatedNel[ZoneResponse.Error, Unit])
    extends ZoneResponse
final case class CreateMemberResponse(
    result: ValidatedNel[ZoneResponse.Error, Member])
    extends ZoneResponse
final case class UpdateMemberResponse(
    result: ValidatedNel[ZoneResponse.Error, Unit])
    extends ZoneResponse
final case class CreateAccountResponse(
    result: ValidatedNel[ZoneResponse.Error, Account])
    extends ZoneResponse
final case class UpdateAccountResponse(
    result: ValidatedNel[ZoneResponse.Error, Unit])
    extends ZoneResponse
final case class AddTransactionResponse(
    result: ValidatedNel[ZoneResponse.Error, Transaction])
    extends ZoneResponse

sealed abstract class ZoneNotification extends ZoneMessage
case object EmptyZoneNotification extends ZoneNotification
final case class ClientJoinedNotification(connectionId: String,
                                          publicKey: PublicKey)
    extends ZoneNotification
final case class ClientQuitNotification(connectionId: String,
                                        publicKey: PublicKey)
    extends ZoneNotification
final case class ZoneNameChangedNotification(name: Option[String])
    extends ZoneNotification
final case class MemberCreatedNotification(member: Member)
    extends ZoneNotification
final case class MemberUpdatedNotification(member: Member)
    extends ZoneNotification
final case class AccountCreatedNotification(account: Account)
    extends ZoneNotification
final case class AccountUpdatedNotification(actingAs: MemberId,
                                            account: Account)
    extends ZoneNotification
final case class TransactionAddedNotification(transaction: Transaction)
    extends ZoneNotification
final case class ZoneStateNotification(zone: Zone,
                                       connectedClients: Map[String, PublicKey])
    extends ZoneNotification
final case class PingNotification(unit: Unit) extends ZoneNotification
