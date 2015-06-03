package com.dhpcs.liquidity.models

import java.util.UUID

import play.api.libs.json._

case class MemberId(id: UUID) extends Identifier

object MemberId extends IdentifierCompanion[MemberId]

case class Member(name: String,
                  publicKey: PublicKey)

// TODO: Common base class here too?
object Member {

  implicit val MemberFormat = Json.format[Member]

}