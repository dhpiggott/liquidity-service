package com.dhpcs.liquidity.model

import java.util.UUID

import okio.ByteString
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._

object ValueFormat {
  def apply[A, B: Format](apply: B => A, unapply: A => B): Format[A] = Format(
    Reads(
      json => Reads.of[B].reads(json).map(apply(_))
    ),
    Writes(
      a => Writes.of[B].writes(unapply(a))
    )
  )
}

final case class PublicKey(value: ByteString) {
  lazy val fingerprint: String = value.sha256.hex
}

object PublicKey {

  implicit final val PublicKeyFormat: Format[PublicKey] = ValueFormat[PublicKey, String](
    publicKeyBase64 => PublicKey(ByteString.decodeBase64(publicKeyBase64)),
    publicKey => publicKey.value.base64)

  def apply(value: Array[Byte]): PublicKey = PublicKey(ByteString.of(value: _*))

}

final case class MemberId(id: Long)

object MemberId {
  implicit final val MemberIdFormat: Format[MemberId] = ValueFormat[MemberId, Long](MemberId(_), _.id)
}

final case class Member(id: MemberId,
                        ownerPublicKey: PublicKey,
                        name: Option[String] = None,
                        metadata: Option[com.google.protobuf.struct.Struct] = None)

object Member {
  implicit final val MemberFormat: Format[Member] = Json.format[Member]
}

final case class AccountId(id: Long)

object AccountId {
  implicit final val AccountIdFormat: Format[AccountId] = ValueFormat[AccountId, Long](AccountId(_), _.id)
}

final case class Account(id: AccountId,
                         ownerMemberIds: Set[MemberId],
                         name: Option[String] = None,
                         metadata: Option[com.google.protobuf.struct.Struct] = None)

object Account {
  implicit final val AccountFormat: Format[Account] = Json.format[Account]
}

final case class TransactionId(id: Long)

object TransactionId {
  implicit final val TransactionIdFormat: Format[TransactionId] =
    ValueFormat[TransactionId, Long](TransactionId(_), _.id)
}

final case class Transaction(id: TransactionId,
                             from: AccountId,
                             to: AccountId,
                             value: BigDecimal,
                             creator: MemberId,
                             created: Long,
                             description: Option[String] = None,
                             metadata: Option[com.google.protobuf.struct.Struct] = None) {
  require(value >= 0)
  require(created >= 0)
}

object Transaction {
  implicit final val TransactionFormat: Format[Transaction] = (
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
}

final case class ZoneId(id: UUID)

object ZoneId {

  implicit final val ZoneIdFormat: Format[ZoneId] = ValueFormat[ZoneId, UUID](ZoneId(_), _.id)

  def generate: ZoneId = apply(UUID.randomUUID)

}

final case class Zone(id: ZoneId,
                      equityAccountId: AccountId,
                      members: Map[MemberId, Member],
                      accounts: Map[AccountId, Account],
                      transactions: Map[TransactionId, Transaction],
                      created: Long,
                      expires: Long,
                      name: Option[String] = None,
                      metadata: Option[com.google.protobuf.struct.Struct] = None) {
  require(created >= 0)
  require(expires >= 0)
}

object Zone {
  implicit final val ZoneFormat: Format[Zone] = (
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
