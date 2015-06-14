package com.dhpcs.liquidity.models

import java.security.KeyPairGenerator
import java.util.UUID

import com.dhpcs.json.FormatBehaviors
import com.google.common.io.BaseEncoding
import org.scalatest._
import play.api.data.validation.ValidationError
import play.api.libs.json._

class ZoneSpec extends FunSpec with FormatBehaviors[Zone] with Matchers {

  describe("A JsValue of the wrong type") {
    it should behave like readError(
      Json.parse("0"),
      JsError(List(
        (__ \ "name", List(ValidationError("error.path.missing"))),
        (__ \ "type", List(ValidationError("error.path.missing"))),
        (__ \ "members", List(ValidationError("error.path.missing"))),
        (__ \ "accounts", List(ValidationError("error.path.missing"))),
        (__ \ "transactions", List(ValidationError("error.path.missing"))),
        (__ \ "lastModified", List(ValidationError("error.path.missing")))
      ))
    )
  }

  describe("A Zone") {
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    implicit val zone = Zone(
      "Dave's zone",
      "test",
      Map(
        MemberId(UUID.fromString("fa781d33-368f-42a5-9c64-0e4b43381c37")) ->
          Member("Dave", PublicKey(publicKeyBytes))
      ),
      Map(
        AccountId(UUID.fromString("f2f4613c-0645-4dec-895b-2812382f4523")) ->
          Account("Dave's account", Set(MemberId(UUID.fromString("fa781d33-368f-42a5-9c64-0e4b43381c37"))))
      ),
      Map(
        TransactionId(UUID.fromString("65b1711c-5747-452c-8975-3f0d36e9efa6")) ->
          Transaction(
            "Dave's lottery win",
            AccountId(UUID.fromString("80ccbec2-79a4-4cfa-8e97-f33fac2aa5ba")),
            AccountId(UUID.fromString("f2f4613c-0645-4dec-895b-2812382f4523")),
            BigDecimal(1000000),
            1433611420487L
          )
      ),
      1433611420487L
    )
    implicit val zoneJson = Json.parse( s"""{"name":"Dave's zone","type":"test","members":{"fa781d33-368f-42a5-9c64-0e4b43381c37":{"name":"Dave","publicKey":"${BaseEncoding.base64.encode(publicKeyBytes)}"}},"accounts":{"f2f4613c-0645-4dec-895b-2812382f4523":{"name":"Dave's account","owners":["fa781d33-368f-42a5-9c64-0e4b43381c37"]}},"transactions":{"65b1711c-5747-452c-8975-3f0d36e9efa6":{"description":"Dave's lottery win","from":"80ccbec2-79a4-4cfa-8e97-f33fac2aa5ba","to":"f2f4613c-0645-4dec-895b-2812382f4523","amount":1000000,"created":1433611420487}},"lastModified":1433611420487}""")
    it should behave like read
    it should behave like write
  }

}