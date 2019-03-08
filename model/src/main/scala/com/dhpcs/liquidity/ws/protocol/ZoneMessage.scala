package com.dhpcs.liquidity.ws.protocol

import akka.http.scaladsl.model.ErrorInfo
import akka.http.scaladsl.model.headers.HttpCredentials
import cats.data.ValidatedNel
import com.dhpcs.liquidity.model._

sealed abstract class ZoneMessage

sealed abstract class ZoneCommand extends ZoneMessage
object ZoneCommand {

  final val RequiredKeySize = 2048
  final val MaximumTagLength = 160
  final val MaximumMetadataSize = 1024

  case object Empty extends ZoneCommand

}

final case class CreateZoneCommand(
    equityOwnerPublicKey: PublicKey,
    equityOwnerName: Option[String],
    equityOwnerMetadata: Option[com.google.protobuf.struct.Struct],
    equityAccountName: Option[String],
    equityAccountMetadata: Option[com.google.protobuf.struct.Struct],
    name: Option[String],
    metadata: Option[com.google.protobuf.struct.Struct])
    extends ZoneCommand
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

    val authorizationNotPresentInMetadata =
      ZoneResponse.Error(code = 0,
                         description = "Authorization not present in metadata.")
    def authorizationNotValid(error: ErrorInfo) =
      ZoneResponse.Error(code = 1,
                         description = s"Authorization not valid: $error.")
    def authorizationNotAnOAuth2BearerToken(other: HttpCredentials) =
      ZoneResponse.Error(code = 2,
                         description =
                           s"Expected OAuth2BearerToken but found $other.")
    val tokenNotASignedJwt =
      ZoneResponse.Error(code = 3, description = "Token must be a signed JWT.")
    val tokenPayloadMustBeJson =
      ZoneResponse.Error(code = 4, description = "Token payload must be JSON.")
    val tokenClaimsMustContainASubject =
      ZoneResponse.Error(code = 5,
                         description = "Token claims must contain a subject.")
    val tokenSubjectMustBeAnRsaPublicKey =
      ZoneResponse.Error(code = 6,
                         description =
                           "Token subject must be an RSA public key.")
    val tokenMustBeSignedBySubjectsPrivateKey =
      ZoneResponse.Error(
        code = 7,
        description =
          "Token must be signed by subject's private key and used between nbf and iat claims.")

    val zoneDoesNotExist =
      ZoneResponse.Error(code = 8, description = "Zone must be created.")
    val noPublicKeys =
      ZoneResponse.Error(code = 9,
                         description =
                           "At least one public key must be specified.")
    val invalidPublicKeyType =
      ZoneResponse.Error(code = 10,
                         description = "Public key type must be RSA.")
    val invalidPublicKey =
      ZoneResponse.Error(code = 11, description = "Public key must be valid.")
    val invalidPublicKeyLength =
      ZoneResponse.Error(
        code = 4,
        description =
          s"Public key length must be ${ZoneCommand.RequiredKeySize} bits.")
    val noMemberIds =
      ZoneResponse.Error(code = 12,
                         description =
                           "At least one member ID must be specified.")
    def memberDoesNotExist(memberId: MemberId) =
      ZoneResponse.Error(code = 13,
                         description = s"Member ${memberId.value} must exist.")
    val memberDoesNotExist =
      ZoneResponse.Error(code = 14, description = "Member must exist.")
    val memberKeyMismatch =
      ZoneResponse.Error(code = 15,
                         description =
                           "Client public key must match that of the member.")
    val accountDoesNotExist =
      ZoneResponse.Error(code = 16, description = "Account must exist.")
    val accountOwnerKeyMismatch =
      ZoneResponse.Error(code = 17,
                         description =
                           "Client public key must match that of an owner.")
    val accountOwnerMismatch =
      ZoneResponse.Error(code = 18,
                         description = "Member must be an account owner.")
    val sourceAccountDoesNotExist =
      ZoneResponse.Error(code = 19, description = "Source account must exist.")
    val destinationAccountDoesNotExist =
      ZoneResponse.Error(code = 20,
                         description = "Destination account must exist.")
    val reflexiveTransaction =
      ZoneResponse.Error(
        code = 21,
        description = "Destination account must not also be the source account.")
    val negativeTransactionValue =
      ZoneResponse.Error(code = 22,
                         description = "Transaction value must be positive.")
    val insufficientBalance =
      ZoneResponse.Error(
        code = 23,
        description =
          "Source account must have a balance greater than or equal to the transaction value.")
    val tagLengthExceeded =
      ZoneResponse.Error(
        code = 24,
        description =
          s"Tag length must be less than ${ZoneCommand.MaximumTagLength} characters.")
    val metadataLengthExceeded =
      ZoneResponse.Error(
        code = 25,
        description =
          s"Metadata size must be less than ${ZoneCommand.MaximumMetadataSize} bytes.")
  }

  final case class Error(code: Int, description: String)

  case object Empty extends ZoneResponse

}

final case class CreateZoneResponse(
    result: ValidatedNel[ZoneResponse.Error, Zone])
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
object ZoneNotification {
  case object Empty extends ZoneNotification
}
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
final case class ZoneStateNotification(zone: Option[Zone],
                                       connectedClients: Map[String, PublicKey])
    extends ZoneNotification
final case class PingNotification() extends ZoneNotification
