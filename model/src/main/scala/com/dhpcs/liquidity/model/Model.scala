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

case class PublicKey(value: ByteString) {
  lazy val fingerprint: String = value.sha256.hex
}

object PublicKey {

  implicit final val PublicKeyFormat: Format[PublicKey] = ValueFormat[PublicKey, String](
    publicKeyBase64 => PublicKey(ByteString.decodeBase64(publicKeyBase64)),
    publicKey => publicKey.value.base64)

  def apply(value: Array[Byte]): PublicKey = PublicKey(ByteString.of(value: _*))

}

case class MemberId(id: Int)

object MemberId {
  implicit final val MemberIdFormat: Format[MemberId] = ValueFormat[MemberId, Int](MemberId(_), _.id)
}

case class Member(id: MemberId,
                  ownerPublicKey: PublicKey,
                  name: Option[String] = None,
                  metadata: Option[JsObject] = None)

object Member {
  implicit final val MemberFormat: Format[Member] = Json.format[Member]
}

case class AccountId(id: Int)

object AccountId {
  implicit final val AccountIdFormat: Format[AccountId] = ValueFormat[AccountId, Int](AccountId(_), _.id)
}

case class Account(id: AccountId,
                   ownerMemberIds: Set[MemberId],
                   name: Option[String] = None,
                   metadata: Option[JsObject] = None)

object Account {
  implicit final val AccountFormat: Format[Account] = Json.format[Account]
}

case class TransactionId(id: Int)

object TransactionId {
  implicit final val TransactionIdFormat: Format[TransactionId] =
    ValueFormat[TransactionId, Int](TransactionId(_), _.id)
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

case class ZoneId(id: UUID)

object ZoneId {

  implicit final val ZoneIdFormat: Format[ZoneId] = ValueFormat[ZoneId, UUID](ZoneId(_), _.id)

  def generate: ZoneId = apply(UUID.randomUUID)

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
