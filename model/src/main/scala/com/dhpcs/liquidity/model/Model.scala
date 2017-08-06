package com.dhpcs.liquidity.model

import java.util.UUID

import akka.actor.ActorPath
import okio.ByteString

final case class PublicKey(value: ByteString) {
  lazy val fingerprint: String = value.sha256.hex
}

object PublicKey {
  def apply(value: Array[Byte]): PublicKey = PublicKey(ByteString.of(value: _*))
}

final case class MemberId(id: Long)

final case class Member(id: MemberId,
                        ownerPublicKeys: Set[PublicKey],
                        name: Option[String] = None,
                        metadata: Option[com.google.protobuf.struct.Struct] = None)

final case class AccountId(id: Long)

final case class Account(id: AccountId,
                         ownerMemberIds: Set[MemberId],
                         name: Option[String] = None,
                         metadata: Option[com.google.protobuf.struct.Struct] = None)

final case class TransactionId(id: Long)

final case class Transaction(id: TransactionId,
                             from: AccountId,
                             to: AccountId,
                             value: BigDecimal,
                             creator: MemberId,
                             created: Long,
                             description: Option[String] = None,
                             metadata: Option[com.google.protobuf.struct.Struct] = None)

final case class ZoneId(id: UUID) {
  def persistenceId: String = s"${ZoneId.PersistenceIdPrefix}$id"
}

object ZoneId {

  final val PersistenceIdPrefix = "zone-"

  def apply(persistenceId: String): ZoneId =
    ZoneId(UUID.fromString(persistenceId.stripPrefix(PersistenceIdPrefix)))

  def generate: ZoneId = ZoneId(UUID.randomUUID)

}

final case class Zone(id: ZoneId,
                      equityAccountId: AccountId,
                      members: Map[MemberId, Member],
                      accounts: Map[AccountId, Account],
                      transactions: Map[TransactionId, Transaction],
                      created: Long,
                      expires: Long,
                      name: Option[String] = None,
                      metadata: Option[com.google.protobuf.struct.Struct] = None)

final case class ZoneState(zone: Option[Zone],
                           balances: Map[AccountId, BigDecimal],
                           clientConnections: Map[ActorPath, PublicKey])
