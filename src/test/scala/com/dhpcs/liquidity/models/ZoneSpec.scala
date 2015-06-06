package com.dhpcs.liquidity.models

import java.security.KeyPairGenerator

import com.google.common.io.BaseEncoding
import org.scalatest._
import play.api.data.validation.ValidationError
import play.api.libs.json._

class ZoneSpec extends FunSpec with Matchers {

  def decodeError(badZoneJson: JsValue, jsError: JsError) =
    it(s"$badZoneJson should fail to decode with error $jsError") {
      Json.fromJson[Zone](badZoneJson) should be(jsError)
    }

  def decode(implicit zoneJson: JsValue, zone: Zone) =
    it(s"$zoneJson should decode to $zone") {
      zoneJson.as[Zone] should be(zone)
    }

  def encode(implicit zone: Zone, zoneJson: JsValue) =
    it(s"$zone should encode to $zoneJson") {
      Json.toJson(zone) should be(zoneJson)
    }

  describe("A JsValue of the wrong type") {
    it should behave like decodeError(
      Json.parse("0"),
      JsError(List(
        (__ \ "name", List(ValidationError("error.path.missing"))),
        (__ \ "transactions", List(ValidationError("error.path.missing"))),
        (__ \ "accounts", List(ValidationError("error.path.missing"))),
        (__ \ "type", List(ValidationError("error.path.missing"))),
        (__ \ "members", List(ValidationError("error.path.missing"))),
        (__ \ "lastModified", List(ValidationError("error.path.missing")))
      ))
    )
  }

  describe("A Zone") {
    val memberId = MemberId.generate
    // TODO: Be consistent with public key and id vals across all tests
    val memberRawPublicKey = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    val memberAccountId = AccountId.generate
    val lotteryFundAccountId = AccountId.generate
    val lastModified = System.currentTimeMillis
    implicit val zone = Zone(
      "Dave's zone",
      "test",
      Map(
        memberId -> Member("Dave", PublicKey(memberRawPublicKey))
      ),
      Map(
        memberAccountId -> Account("Dave's account", Set(memberId))
      ),
      Seq(
        Transaction("Dave's lottery win", lotteryFundAccountId, memberAccountId, BigDecimal(1000000))
      ),
      lastModified
    )
    implicit val zoneJson = Json.parse( s"""{"name":"Dave's zone","type":"test","members":{"${memberId.id}":{"name":"Dave","publicKey":"${BaseEncoding.base64.encode(memberRawPublicKey)}"}},"accounts":{"${memberAccountId.id}":{"name":"Dave's account","owners":["${memberId.id}"]}},"transactions":[{"description":"Dave's lottery win","from":"${lotteryFundAccountId.id}","to":"${memberAccountId.id}","amount":1000000}],"lastModified":$lastModified}""")
    it should behave like decode
    it should behave like encode
  }

}