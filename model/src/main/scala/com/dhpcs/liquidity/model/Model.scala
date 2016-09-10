package com.dhpcs.liquidity.model

import java.util
import java.util.UUID

import okio.ByteString
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json.{Format, JsObject, JsPath, Json, Reads, Writes}

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

case class MemberId(id: Int)

object MemberId {
  implicit final val MemberIdFormat = ValueFormat[MemberId, Int](apply, _.id)
}

case class PublicKey(value: Array[Byte]) {
  lazy val fingerprint = ByteString.of(value: _*).sha256.hex

  override def equals(that: Any) = that match {
    case that: PublicKey => util.Arrays.equals(this.value, that.value)
    case _ => false
  }

  override def hashCode = util.Arrays.hashCode(value)

  override def toString = s"$productPrefix(${ByteString.of(value: _*).base64})"
}

object PublicKey {
  implicit final val PublicKeyFormat = ValueFormat[PublicKey, String](
    publicKeyBase64 => PublicKey(ByteString.decodeBase64(publicKeyBase64).toByteArray),
    publicKey => ByteString.of(publicKey.value: _*).base64)
}

case class Member(id: MemberId,
                  ownerPublicKey: PublicKey,
                  name: Option[String] = None,
                  metadata: Option[JsObject] = None)

object Member {
  implicit final val MemberFormat = Json.format[Member]
}

case class AccountId(id: Int)

object AccountId {
  implicit final val AccountIdFormat = ValueFormat[AccountId, Int](apply, _.id)
}

case class Account(id: AccountId,
                   ownerMemberIds: Set[MemberId],
                   name: Option[String] = None,
                   metadata: Option[JsObject] = None)

object Account {
  implicit final val AccountFormat = Json.format[Account]
}

case class TransactionId(id: Int)

object TransactionId {
  implicit final val TransactionIdFormat = ValueFormat[TransactionId, Int](apply, _.id)
}

case class Transaction(id: TransactionId,
                       from: AccountId,
                       to: AccountId,
                       value: BigDecimal,
                       creator: MemberId,
                       created: Long,
                       description: Option[String] = None,
                       metadata: Option[JsObject] = None) {
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
      (JsPath \ "metadata").formatNullable[JsObject]
    ) ((id, from, to, value, creator, created, description, metadata) =>
    Transaction(
      id,
      from,
      to,
      value,
      creator,
      created,
      description,
      metadata
    ), transaction =>
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

case class ZoneId(id: UUID)

object ZoneId {
  implicit final val ZoneIdFormat = ValueFormat[ZoneId, UUID](apply, _.id)

  def generate = apply(UUID.randomUUID)
}

case class Zone(id: ZoneId,
                equityAccountId: AccountId,
                members: Map[MemberId, Member],
                accounts: Map[AccountId, Account],
                transactions: Map[TransactionId, Transaction],
                created: Long,
                expires: Long,
                name: Option[String] = None,
                metadata: Option[JsObject] = None) {
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
      (JsPath \ "metadata").formatNullable[JsObject]
    ) ((id, equityAccountId, members, accounts, transactions, created, expires, name, metadata) =>
    Zone(
      id,
      equityAccountId,
      members.map(e => e.id -> e).toMap,
      accounts.map(e => e.id -> e).toMap,
      transactions.map(e => e.id -> e).toMap,
      created,
      expires,
      name,
      metadata
    ), zone =>
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

sealed trait Event {
  def timestamp: Long
}

case class ZoneCreatedEvent(timestamp: Long, zone: Zone) extends Event

object ZoneCreatedEvent {
  implicit final val ZoneCreatedEventFormat = Json.format[ZoneCreatedEvent]
}

case class ZoneJoinedEvent(timestamp: Long, clientConnectionActorPath: String, publicKey: PublicKey) extends Event

object ZoneJoinedEvent {
  implicit final val ZoneJoinedEventFormat = Json.format[ZoneJoinedEvent]
}

case class ZoneQuitEvent(timestamp: Long, clientConnectionActorPath: String) extends Event

object ZoneQuitEvent {
  implicit final val ZoneQuitEventFormat = Json.format[ZoneQuitEvent]
}

case class ZoneNameChangedEvent(timestamp: Long, name: Option[String]) extends Event

object ZoneNameChangedEvent {
  implicit final val ZoneNameChangedEventFormat = Json.format[ZoneNameChangedEvent]
}

case class MemberCreatedEvent(timestamp: Long, member: Member) extends Event

object MemberCreatedEvent {
  implicit final val MemberCreatedEventFormat = Json.format[MemberCreatedEvent]
}

case class MemberUpdatedEvent(timestamp: Long, member: Member) extends Event

object MemberUpdatedEvent {
  implicit final val MemberUpdatedEventFormat = Json.format[MemberUpdatedEvent]
}

case class AccountCreatedEvent(timestamp: Long, account: Account) extends Event

object AccountCreatedEvent {
  implicit final val AccountCreatedEventFormat = Json.format[AccountCreatedEvent]
}

case class AccountUpdatedEvent(timestamp: Long, account: Account) extends Event

object AccountUpdatedEvent {
  implicit final val AccountUpdatedEventFormat = Json.format[AccountUpdatedEvent]
}

case class TransactionAddedEvent(timestamp: Long, transaction: Transaction) extends Event

object TransactionAddedEvent {
  implicit final val TransactionAddedEventFormat = Json.format[TransactionAddedEvent]
}
