package com.dhpcs.liquidity.models

import java.util.UUID

import play.api.libs.json.JsObject

@SerialVersionUID(9031065644074374752L)
case class MemberId(id: Int)

@SerialVersionUID(5447998374801439950L)
case class PublicKey(value: Array[Byte])

@SerialVersionUID(3471055225647050981L)
case class Member(id: MemberId,
                  ownerPublicKey: PublicKey,
                  name: Option[String] = None,
                  metadata: Option[JsObject] = None)

@SerialVersionUID(7841209810226048195L)
case class AccountId(id: Int)

@SerialVersionUID(4155509960789482708L)
case class Account(id: AccountId,
                   ownerMemberIds: Set[MemberId],
                   name: Option[String] = None,
                   metadata: Option[JsObject] = None)

@SerialVersionUID(-1493946661525521370L)
case class TransactionId(id: Int)

@SerialVersionUID(5368705010469276055L)
case class Transaction(id: TransactionId,
                       from: AccountId,
                       to: AccountId,
                       value: BigDecimal,
                       creator: MemberId,
                       created: Long,
                       description: Option[String] = None,
                       metadata: Option[JsObject] = None)

@SerialVersionUID(-8002926089768713775L)
case class ZoneId(id: UUID)

@SerialVersionUID(-4861106837660151105L)
case class Zone(id: ZoneId,
                equityAccountId: AccountId,
                members: Map[MemberId, Member],
                accounts: Map[AccountId, Account],
                transactions: Map[TransactionId, Transaction],
                created: Long,
                expires: Long,
                name: Option[String] = None,
                metadata: Option[JsObject] = None)
