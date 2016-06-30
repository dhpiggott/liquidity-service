package com.dhpcs.liquidity.models

import play.api.libs.json._

case class MemberId(id: Int) extends IntIdentifier

object MemberId extends IntIdentifierCompanion[MemberId]

case class Member(id: MemberId,
                  ownerPublicKey: PublicKey,
                  name: Option[String] = None,
                  metadata: Option[JsObject] = None)

object Member {

  implicit val MemberFormat = Json.format[Member]

}
