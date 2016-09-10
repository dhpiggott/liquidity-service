package com.dhpcs.liquidity.models

import actors.ZoneValidator._
import akka.persistence.journal._
import com.dhpcs.liquidity.model

import scala.language.implicitConversions

class LegacyReadEventAdapter extends ReadEventAdapter {

  override def fromJournal(event: Any, manifest: String): EventSeq =
    event match {
      case _: Event => SingleEventSeq(event match {
        case ZoneCreatedEvent(timestamp, zone) =>
          model.ZoneCreatedEvent(timestamp, zone)
        case ZoneJoinedEvent(timestamp, clientConnection, publicKey) =>
          model.ZoneJoinedEvent(timestamp, clientConnection.path.toSerializationFormat, publicKey)
        case ZoneQuitEvent(timestamp, clientConnection) =>
          model.ZoneQuitEvent(timestamp, clientConnection.path.toSerializationFormat)
        case ZoneNameChangedEvent(timestamp, name) =>
          model.ZoneNameChangedEvent(timestamp, name)
        case MemberCreatedEvent(timestamp, member) =>
          model.MemberCreatedEvent(timestamp, member)
        case MemberUpdatedEvent(timestamp, member) =>
          model.MemberUpdatedEvent(timestamp, member)
        case AccountCreatedEvent(timestamp, account) =>
          model.AccountCreatedEvent(timestamp, account)
        case AccountUpdatedEvent(timestamp, account) =>
          model.AccountUpdatedEvent(timestamp, account)
        case TransactionAddedEvent(timestamp, transaction) =>
          model.TransactionAddedEvent(timestamp, transaction)
      })
      case other => EmptyEventSeq
    }

  private[this] implicit def fromLegacyPublicKey(publicKey: PublicKey): model.PublicKey =
    model.PublicKey(publicKey.value)

  private[this] implicit def fromLegacyMemberId(member: MemberId): model.MemberId =
    model.MemberId(member.id)

  private[this] implicit def fromLegacyMemberIds(memberIds: Set[MemberId]): Set[model.MemberId] =
    memberIds.map(fromLegacyMemberId)

  private[this] implicit def fromLegacyMember(member: Member): model.Member =
    model.Member(member.id, member.ownerPublicKey, member.name, member.metadata)

  private[this] implicit def fromLegacyMembers(members: Map[MemberId, Member]): Map[model.MemberId, model.Member] =
    members.map { case (memberId, member) => (memberId: model.MemberId, member: model.Member) }

  private[this] implicit def fromLegacyAccountId(accountId: AccountId): model.AccountId =
    model.AccountId(accountId.id)

  private[this] implicit def fromLegacyAccount(account: Account): model.Account =
    model.Account(account.id, account.ownerMemberIds, account.name, account.metadata)

  private[this] implicit def fromLegacyAccounts(accounts: Map[AccountId, Account]): Map[model.AccountId, model.Account] =
    accounts.map { case (accountId, account) => (accountId: model.AccountId, account: model.Account) }

  private[this] implicit def fromLegacyTransactionId(transactionId: TransactionId): model.TransactionId =
    model.TransactionId(transactionId.id)

  private[this] implicit def fromLegacyTransaction(transaction: Transaction): model.Transaction =
    model.Transaction(transaction.id, transaction.from, transaction.to, transaction.value, transaction.creator, transaction.created, transaction.description, transaction.metadata)

  private[this] implicit def fromLegacyTransactions(transactions: Map[TransactionId, Transaction]): Map[model.TransactionId, model.Transaction] =
    transactions.map { case (transactionId, transaction) => (transactionId: model.TransactionId, transaction: model.Transaction) }

  private[this] implicit def fromLegacyZoneId(zoneId: ZoneId): model.ZoneId =
    model.ZoneId(zoneId.id)

  private[this] implicit def fromLegacyZone(zone: Zone): model.Zone =
    model.Zone(zone.id, zone.equityAccountId, zone.members, zone.accounts, zone.transactions, zone.created, zone.expires, zone.name, zone.metadata)

}
