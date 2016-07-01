package com.dhpcs.liquidity.protocol

import play.api.libs.json.{JsObject, Json}

case class MemberId(id: Int) extends IntIdentifier

object MemberId extends IntIdentifierCompanion[MemberId]

case class Member(id: MemberId,
                  ownerPublicKey: PublicKey,
                  name: Option[String] = None,
                  metadata: Option[JsObject] = None)

object Member {
  implicit final val MemberFormat = Json.format[Member]
}
