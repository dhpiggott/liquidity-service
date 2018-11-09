package com.dhpcs.liquidity.model

import java.time.Instant

import okio.ByteString

final case class PublicKey(value: ByteString) extends AnyVal {
  def fingerprint: String = value.sha256.hex
}

object PublicKey {
  def apply(value: Array[Byte]): PublicKey = PublicKey(ByteString.of(value: _*))
}

final case class MemberId(value: String) extends AnyVal

final case class Member(id: MemberId,
                        ownerPublicKeys: Set[PublicKey],
                        name: Option[String],
                        metadata: Option[com.google.protobuf.struct.Struct])

final case class AccountId(value: String) extends AnyVal

final case class Account(id: AccountId,
                         ownerMemberIds: Set[MemberId],
                         name: Option[String],
                         metadata: Option[com.google.protobuf.struct.Struct])

final case class TransactionId(value: String) extends AnyVal

final case class Transaction(
    id: TransactionId,
    from: AccountId,
    to: AccountId,
    value: BigDecimal,
    creator: MemberId,
    created: Instant,
    description: Option[String],
    metadata: Option[com.google.protobuf.struct.Struct])

final case class ZoneId(value: String) extends AnyVal {
  def persistenceId: String = s"${ZoneId.PersistenceIdPrefix}$value"
}

object ZoneId {

  final val PersistenceIdPrefix = "zone-"

  def fromPersistentEntityId(persistenceId: String): ZoneId =
    ZoneId(persistenceId.stripPrefix(PersistenceIdPrefix))

}

final case class Zone(id: ZoneId,
                      equityAccountId: AccountId,
                      members: Map[MemberId, Member],
                      accounts: Map[AccountId, Account],
                      transactions: Map[TransactionId, Transaction],
                      created: Instant,
                      expires: Instant,
                      name: Option[String],
                      metadata: Option[com.google.protobuf.struct.Struct])
