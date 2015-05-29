package com.dhpcs.liquidity.models

import java.security.KeyPairGenerator

import org.scalatest._
import play.api.libs.json.Json

class MemberSpec extends FlatSpec with Matchers {

  "Member formatting" should "be reversible" in {
    val member = Member("Dave", PublicKey(KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded))
    note(s"member: $member")
    val memberJson = Json.toJson(member)
    note(s"memberJson: $memberJson")
    memberJson.as[Member] should be(member)
  }

}