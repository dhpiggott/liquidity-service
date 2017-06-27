package com.dhpcs.liquidity.ws.protocol.legacy

import java.util.UUID

import com.dhpcs.jsonrpc.Message.{MessageFormats, objectFormat}
import com.dhpcs.jsonrpc._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.ws.protocol.legacy.LegacyWsProtocol.LegacyModelFormats._
import okio.ByteString
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads.min
import play.api.libs.json._

object LegacyWsProtocol {

  object LegacyModelFormats {

    object ValueFormat {
      def apply[A, B: Format](apply: B => A, unapply: A => B): Format[A] = Format(
        Reads.of[B].map(apply),
        Writes.of[B].contramap(unapply)
      )
    }

    implicit final lazy val PublicKeyFormat: Format[PublicKey] = ValueFormat[PublicKey, String](
      publicKeyBase64 => PublicKey(ByteString.decodeBase64(publicKeyBase64)),
      publicKey => publicKey.value.base64)

    implicit final lazy val MemberIdFormat: Format[MemberId] = ValueFormat[MemberId, Long](MemberId, _.id)

    implicit final lazy val MemberFormat: Format[Member] = Json.format[Member]

    implicit final lazy val AccountIdFormat: Format[AccountId] = ValueFormat[AccountId, Long](AccountId, _.id)

    implicit final lazy val AccountFormat: Format[Account] = Json.format[Account]

    implicit final lazy val TransactionIdFormat: Format[TransactionId] =
      ValueFormat[TransactionId, Long](TransactionId, _.id)

    implicit final lazy val TransactionFormat: Format[Transaction] = (
      (JsPath \ "id").format[TransactionId] and
        (JsPath \ "from").format[AccountId] and
        (JsPath \ "to").format[AccountId] and
        (JsPath \ "value").format(min[BigDecimal](0)) and
        (JsPath \ "creator").format[MemberId] and
        (JsPath \ "created").format(min[Long](0)) and
        (JsPath \ "description").formatNullable[String] and
        (JsPath \ "metadata").formatNullable[com.google.protobuf.struct.Struct]
    )(
      (id, from, to, value, creator, created, description, metadata) =>
        Transaction(
          id,
          from,
          to,
          value,
          creator,
          created,
          description,
          metadata
      ),
      transaction =>
        (transaction.id,
         transaction.from,
         transaction.to,
         transaction.value,
         transaction.creator,
         transaction.created,
         transaction.description,
         transaction.metadata)
    )

    implicit final lazy val ZoneIdFormat: Format[ZoneId] = ValueFormat[ZoneId, UUID](ZoneId(_), _.id)

    implicit final lazy val ZoneFormat: Format[Zone] = (
      (JsPath \ "id").format[ZoneId] and
        (JsPath \ "equityAccountId").format[AccountId] and
        (JsPath \ "members").format[Seq[Member]] and
        (JsPath \ "accounts").format[Seq[Account]] and
        (JsPath \ "transactions").format[Seq[Transaction]] and
        (JsPath \ "created").format(min[Long](0)) and
        (JsPath \ "expires").format(min[Long](0)) and
        (JsPath \ "name").formatNullable[String] and
        (JsPath \ "metadata").formatNullable[com.google.protobuf.struct.Struct]
    )(
      (id, equityAccountId, members, accounts, transactions, created, expires, name, metadata) =>
        Zone(
          id,
          equityAccountId,
          members.map(e => e.id      -> e).toMap,
          accounts.map(e => e.id     -> e).toMap,
          transactions.map(e => e.id -> e).toMap,
          created,
          expires,
          name,
          metadata
      ),
      zone =>
        (zone.id,
         zone.equityAccountId,
         zone.members.values.toSeq,
         zone.accounts.values.toSeq,
         zone.transactions.values.toSeq,
         zone.created,
         zone.expires,
         zone.name,
         zone.metadata)
    )

  }

  sealed abstract class Message
  sealed abstract class Command      extends Message
  sealed abstract class Response     extends Message
  sealed abstract class Notification extends Message

  sealed abstract class ZoneCommand extends Command
  final case class CreateZoneCommand(equityOwnerPublicKey: PublicKey,
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

  object Command extends CommandCompanion[Command] {

    private final lazy val AddTransactionCommandFormat: Format[AddTransactionCommand] = (
      (JsPath \ "zoneId").format[ZoneId] and
        (JsPath \ "actingAs").format[MemberId] and
        (JsPath \ "from").format[AccountId] and
        (JsPath \ "to").format[AccountId] and
        (JsPath \ "value").format(min[BigDecimal](0)) and
        (JsPath \ "description").formatNullable[String] and
        (JsPath \ "metadata").formatNullable[com.google.protobuf.struct.Struct]
    )(
      (zoneId, actingAs, from, to, value, description, metadata) =>
        AddTransactionCommand(
          zoneId,
          actingAs,
          from,
          to,
          value,
          description,
          metadata
      ),
      addTransactionCommand =>
        (addTransactionCommand.zoneId,
         addTransactionCommand.actingAs,
         addTransactionCommand.from,
         addTransactionCommand.to,
         addTransactionCommand.value,
         addTransactionCommand.description,
         addTransactionCommand.metadata)
    )

    override final lazy val CommandFormats = MessageFormats(
      "createZone"     -> Json.format[CreateZoneCommand],
      "joinZone"       -> Json.format[JoinZoneCommand],
      "quitZone"       -> Json.format[QuitZoneCommand],
      "changeZoneName" -> Json.format[ChangeZoneNameCommand],
      "createMember"   -> Json.format[CreateMemberCommand],
      "updateMember"   -> Json.format[UpdateMemberCommand],
      "createAccount"  -> Json.format[CreateAccountCommand],
      "updateAccount"  -> Json.format[UpdateAccountCommand],
      "addTransaction" -> AddTransactionCommandFormat
    )
  }

  sealed abstract class ZoneResponse                                              extends Response
  final case class ErrorResponse(error: String)                                   extends ZoneResponse
  sealed abstract class SuccessResponse                                           extends ZoneResponse
  final case class CreateZoneResponse(zone: Zone)                                 extends SuccessResponse
  final case class JoinZoneResponse(zone: Zone, connectedClients: Set[PublicKey]) extends SuccessResponse
  case object QuitZoneResponse                                                    extends SuccessResponse
  case object ChangeZoneNameResponse                                              extends SuccessResponse
  final case class CreateMemberResponse(member: Member)                           extends SuccessResponse
  case object UpdateMemberResponse                                                extends SuccessResponse
  final case class CreateAccountResponse(account: Account)                        extends SuccessResponse
  case object UpdateAccountResponse                                               extends SuccessResponse
  final case class AddTransactionResponse(transaction: Transaction)               extends SuccessResponse

  object SuccessResponse extends ResponseCompanion[SuccessResponse] {
    override final lazy val ResponseFormats = MessageFormats(
      "createZone"     -> Json.format[CreateZoneResponse],
      "joinZone"       -> Json.format[JoinZoneResponse],
      "quitZone"       -> objectFormat(QuitZoneResponse),
      "changeZoneName" -> objectFormat(ChangeZoneNameResponse),
      "createMember"   -> Json.format[CreateMemberResponse],
      "updateMember"   -> objectFormat(UpdateMemberResponse),
      "createAccount"  -> Json.format[CreateAccountResponse],
      "updateAccount"  -> objectFormat(UpdateAccountResponse),
      "addTransaction" -> Json.format[AddTransactionResponse]
    )
  }

  final case class SupportedVersionsNotification(compatibleVersionNumbers: Set[Long])     extends Notification
  case object KeepAliveNotification                                                       extends Notification
  sealed abstract class ZoneNotification                                                  extends Notification
  final case class ClientJoinedZoneNotification(zoneId: ZoneId, publicKey: PublicKey)     extends ZoneNotification
  final case class ClientQuitZoneNotification(zoneId: ZoneId, publicKey: PublicKey)       extends ZoneNotification
  final case class ZoneTerminatedNotification(zoneId: ZoneId)                             extends ZoneNotification
  final case class ZoneNameChangedNotification(zoneId: ZoneId, name: Option[String])      extends ZoneNotification
  final case class MemberCreatedNotification(zoneId: ZoneId, member: Member)              extends ZoneNotification
  final case class MemberUpdatedNotification(zoneId: ZoneId, member: Member)              extends ZoneNotification
  final case class AccountCreatedNotification(zoneId: ZoneId, account: Account)           extends ZoneNotification
  final case class AccountUpdatedNotification(zoneId: ZoneId, account: Account)           extends ZoneNotification
  final case class TransactionAddedNotification(zoneId: ZoneId, transaction: Transaction) extends ZoneNotification

  object Notification extends NotificationCompanion[Notification] {
    override final lazy val NotificationFormats = MessageFormats(
      "supportedVersions" -> Json.format[SupportedVersionsNotification],
      "keepAlive"         -> objectFormat(KeepAliveNotification),
      "clientJoinedZone"  -> Json.format[ClientJoinedZoneNotification],
      "clientQuitZone"    -> Json.format[ClientQuitZoneNotification],
      "zoneTerminated"    -> Json.format[ZoneTerminatedNotification],
      "zoneNameChanged"   -> Json.format[ZoneNameChangedNotification],
      "memberCreated"     -> Json.format[MemberCreatedNotification],
      "memberUpdated"     -> Json.format[MemberUpdatedNotification],
      "accountCreated"    -> Json.format[AccountCreatedNotification],
      "accountUpdated"    -> Json.format[AccountUpdatedNotification],
      "transactionAdded"  -> Json.format[TransactionAddedNotification]
    )
  }
}
