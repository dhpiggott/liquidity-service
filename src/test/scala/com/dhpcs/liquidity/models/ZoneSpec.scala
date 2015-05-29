package com.dhpcs.liquidity.models

import java.security.KeyPairGenerator

import org.scalatest._
import play.api.libs.json.Json

class ZoneSpec extends FlatSpec with Matchers {

  "Zone formatting" should "be reversible" in {
    val memberId = MemberId.generate
    val accountId = AccountId.generate
    val zone = Zone(
      "Dave's zone",
      "test",
      Map(
        memberId -> Member("Dave", PublicKey(KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded))
      ),
      Map(
        accountId -> Account("Dave's account", Set(memberId))
      ),
      Seq(
        Transaction("Dave's lottery win", AccountId.generate, accountId, BigDecimal(1))
      ),
      System.currentTimeMillis
    )
    note(s"zone: $zone}")
    val zoneJson = Json.toJson(zone)
    note(s"zoneJson: $zoneJson")
    zoneJson.as[Zone] should be(zone)
  }

}