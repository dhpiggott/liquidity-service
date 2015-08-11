package com.dhpcs.liquidity.models

import java.util.UUID

import play.api.libs.json._

case class MemberId(id: UUID) extends Identifier

object MemberId extends IdentifierCompanion[MemberId]

case class Member(name: Option[String],
                  publicKey: PublicKey,
                  metadata: Option[JsObject] = None)

object Member {

  implicit val MemberFormat = Json.format[Member]

}