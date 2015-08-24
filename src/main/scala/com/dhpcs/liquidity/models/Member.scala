package com.dhpcs.liquidity.models

import play.api.libs.json._

case class MemberId(id: Int) extends IntIdentifier

object MemberId extends IntIdentifierCompanion[MemberId]

case class Member(name: Option[String],
                  publicKey: PublicKey,
                  metadata: Option[JsObject] = None)

object Member {

  implicit val MemberFormat = Json.format[Member]

}