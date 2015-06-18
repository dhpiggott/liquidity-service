package com.dhpcs.liquidity.models

import java.util.UUID

import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._

case class ZoneId(id: UUID) extends Identifier

object ZoneId extends IdentifierCompanion[ZoneId]

// TODO: Maximum member count, payment state, expiry date
case class Zone(name: String,
                zoneType: String,
                equityHolderMemberId: MemberId,
                equityHolderAccountId: AccountId,
                members: Map[MemberId, Member],
                accounts: Map[AccountId, Account],
                transactions: Map[TransactionId, Transaction],
                lastModified: Long) {
  require(lastModified > 0)
}

object Zone {

  implicit val ZoneFormat: Format[Zone] = (
    (JsPath \ "name").format[String] and
      (JsPath \ "type").format[String] and
      (JsPath \ "equityHolderMemberId").format[MemberId] and
      (JsPath \ "equityHolderAccountId").format[AccountId] and
      (JsPath \ "members").format[Map[String, Member]] and
      (JsPath \ "accounts").format[Map[String, Account]] and
      (JsPath \ "transactions").format[Map[String, Transaction]] and
      (JsPath \ "lastModified").format(min[Long](0))
    )((name, zoneType, equityMemberId, equityAccountId, members, accounts, transactions, lastModified) =>
    Zone(
      name,
      zoneType,
      equityMemberId,
      equityAccountId,
      members.map(e => (MemberId(UUID.fromString(e._1)), e._2)),
      accounts.map(e => (AccountId(UUID.fromString(e._1)), e._2)),
      transactions.map(e => (TransactionId(UUID.fromString(e._1)), e._2)),
      lastModified
    ), zone =>
    (zone.name,
      zone.zoneType,
      zone.equityHolderMemberId,
      zone.equityHolderAccountId,
      zone.members.map { case (memberId, member) => (memberId.id.toString, member) },
      zone.accounts.map { case (accountId, account) => (accountId.id.toString, account) },
      zone.transactions.map { case (transactionId, transaction) => (transactionId.id.toString, transaction) },
      zone.lastModified)
    )

  def checkAndUpdateBalances(transaction: Transaction,
                             zone: Zone,
                             balances: Map[AccountId, BigDecimal]): Either[String, Map[AccountId, BigDecimal]] =
    if (!zone.accounts.contains(transaction.from)) {
      Left(s"Invalid transaction source account: ${transaction.from}")
    } else if (!zone.accounts.contains(transaction.to)) {
      Left(s"Invalid transaction destination account: ${transaction.to}")
    } else {
      val newSourceBalance = balances(transaction.from) - transaction.amount
      if (newSourceBalance < 0 && transaction.from != zone.equityHolderAccountId) {
        Left(s"Illegal transaction amount: ${transaction.amount}")
      } else {
        val newDestinationBalance = balances(transaction.to) + transaction.amount
        Right(balances
          + (transaction.from -> newSourceBalance)
          + (transaction.to -> newDestinationBalance))
      }
    }

}