package com.dhpcs.liquidity.models

import java.util.UUID

import play.api.libs.json._

case class MemberId(id: UUID)

object MemberId {

  def apply(): MemberId = MemberId(UUID.randomUUID)

  implicit val memberIdReads =
    __.read[UUID].map(MemberId(_))

  implicit val memberIdWrites = Writes[MemberId] {
    memberId => JsString(memberId.id.toString)
  }

}

case class Member(name: String,
                  publicKey: PublicKey)

object Member {

  implicit val memberFormat = Json.format[Member]

}