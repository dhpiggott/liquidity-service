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
      Json.parse( """0"""),
      JsError(List(
        (__ \ "name", List(ValidationError("error.path.missing"))),
        (__ \ "equityAccountId", List(ValidationError("error.path.missing"))),
        (__ \ "members", List(ValidationError("error.path.missing"))),
        (__ \ "accounts", List(ValidationError("error.path.missing"))),
        (__ \ "transactions", List(ValidationError("error.path.missing"))),
        (__ \ "created", List(ValidationError("error.path.missing")))
      ))
    )
  }

  describe("A Zone") {
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    describe("without metadata") {
      implicit val zone = Zone(
        "Dave's zone",
        AccountId(UUID.fromString("71b60ade-c7f5-4911-b85d-d88719763289")),
        Map(
          MemberId(UUID.fromString("b825a40b-4c05-41e1-a156-0a221d765038")) ->
            Member("Banker", PublicKey(publicKeyBytes)),
          MemberId(UUID.fromString("fa781d33-368f-42a5-9c64-0e4b43381c37")) ->
            Member("Dave", PublicKey(publicKeyBytes))
        ),
        Map(
          AccountId(UUID.fromString("71b60ade-c7f5-4911-b85d-d88719763289")) ->
            Account("Bank", Set(MemberId(UUID.fromString("b825a40b-4c05-41e1-a156-0a221d765038")))),
          AccountId(UUID.fromString("f2f4613c-0645-4dec-895b-2812382f4523")) ->
            Account("Dave's account", Set(MemberId(UUID.fromString("fa781d33-368f-42a5-9c64-0e4b43381c37"))))
        ),
        Map(
          TransactionId(UUID.fromString("65b1711c-5747-452c-8975-3f0d36e9efa6")) ->
            Transaction(
              "Dave's lottery win",
              AccountId(UUID.fromString("89a5e157-a643-4196-be6b-e08bc8e7c28b")),
              AccountId(UUID.fromString("f2f4613c-0645-4dec-895b-2812382f4523")),
              BigDecimal(1000000),
              MemberId(UUID.fromString("b825a40b-4c05-41e1-a156-0a221d765038")),
              1433611420487L
            )
        ),
        1433611420487L
      )
      implicit val zoneJson = Json.parse( s"""{"name":"Dave's zone","equityAccountId":"71b60ade-c7f5-4911-b85d-d88719763289","members":{"b825a40b-4c05-41e1-a156-0a221d765038":{"name":"Banker","publicKey":"${BaseEncoding.base64.encode(publicKeyBytes)}"},"fa781d33-368f-42a5-9c64-0e4b43381c37":{"name":"Dave","publicKey":"${BaseEncoding.base64.encode(publicKeyBytes)}"}},"accounts":{"71b60ade-c7f5-4911-b85d-d88719763289":{"name":"Bank","owners":["b825a40b-4c05-41e1-a156-0a221d765038"]},"f2f4613c-0645-4dec-895b-2812382f4523":{"name":"Dave's account","owners":["fa781d33-368f-42a5-9c64-0e4b43381c37"]}},"transactions":{"65b1711c-5747-452c-8975-3f0d36e9efa6":{"description":"Dave's lottery win","from":"89a5e157-a643-4196-be6b-e08bc8e7c28b","to":"f2f4613c-0645-4dec-895b-2812382f4523","value":1000000,"creator":"b825a40b-4c05-41e1-a156-0a221d765038","created":1433611420487}},"created":1433611420487}""")
      it should behave like read
      it should behave like write
    }
    describe("with metadata") {
      implicit val zone = Zone(
        "Dave's zone",
        AccountId(UUID.fromString("71b60ade-c7f5-4911-b85d-d88719763289")),
        Map(
          MemberId(UUID.fromString("b825a40b-4c05-41e1-a156-0a221d765038")) ->
            Member("Banker", PublicKey(publicKeyBytes)),
          MemberId(UUID.fromString("fa781d33-368f-42a5-9c64-0e4b43381c37")) ->
            Member("Dave", PublicKey(publicKeyBytes))
        ),
        Map(
          AccountId(UUID.fromString("71b60ade-c7f5-4911-b85d-d88719763289")) ->
            Account("Bank", Set(MemberId(UUID.fromString("b825a40b-4c05-41e1-a156-0a221d765038")))),
          AccountId(UUID.fromString("f2f4613c-0645-4dec-895b-2812382f4523")) ->
            Account("Dave's account", Set(MemberId(UUID.fromString("fa781d33-368f-42a5-9c64-0e4b43381c37"))))
        ),
        Map(
          TransactionId(UUID.fromString("65b1711c-5747-452c-8975-3f0d36e9efa6")) ->
            Transaction(
              "Dave's lottery win",
              AccountId(UUID.fromString("89a5e157-a643-4196-be6b-e08bc8e7c28b")),
              AccountId(UUID.fromString("f2f4613c-0645-4dec-895b-2812382f4523")),
              BigDecimal(1000000),
              MemberId(UUID.fromString("b825a40b-4c05-41e1-a156-0a221d765038")),
              1433611420487L
            )
        ),
        1433611420487L,
        Some(
          Json.obj(
            "currency" -> "GBP"
          )
        )
      )
      implicit val zoneJson = Json.parse( s"""{"name":"Dave's zone","equityAccountId":"71b60ade-c7f5-4911-b85d-d88719763289","members":{"b825a40b-4c05-41e1-a156-0a221d765038":{"name":"Banker","publicKey":"${BaseEncoding.base64.encode(publicKeyBytes)}"},"fa781d33-368f-42a5-9c64-0e4b43381c37":{"name":"Dave","publicKey":"${BaseEncoding.base64.encode(publicKeyBytes)}"}},"accounts":{"71b60ade-c7f5-4911-b85d-d88719763289":{"name":"Bank","owners":["b825a40b-4c05-41e1-a156-0a221d765038"]},"f2f4613c-0645-4dec-895b-2812382f4523":{"name":"Dave's account","owners":["fa781d33-368f-42a5-9c64-0e4b43381c37"]}},"transactions":{"65b1711c-5747-452c-8975-3f0d36e9efa6":{"description":"Dave's lottery win","from":"89a5e157-a643-4196-be6b-e08bc8e7c28b","to":"f2f4613c-0645-4dec-895b-2812382f4523","value":1000000,"creator":"b825a40b-4c05-41e1-a156-0a221d765038","created":1433611420487}},"created":1433611420487,"metadata":{"currency":"GBP"}}""")
      it should behave like read
      it should behave like write
    }
  }

}