package com.dhpcs.liquidity.models

import java.security.KeyPairGenerator
import java.util.UUID

import com.dhpcs.json.FormatBehaviors
import okio.ByteString
import org.scalatest.{FunSpec, Matchers}
import play.api.data.validation.ValidationError
import play.api.libs.json.{JsError, Json, __}

class ZoneSpec extends FunSpec with FormatBehaviors[Zone] with Matchers {
  describe("A JsValue of the wrong type")(
    it should behave like readError(
      Json.parse(
        """
          |0""".stripMargin
      ),
      JsError(List(
        (__ \ "id", List(ValidationError("error.path.missing"))),
        (__ \ "equityAccountId", List(ValidationError("error.path.missing"))),
        (__ \ "members", List(ValidationError("error.path.missing"))),
        (__ \ "accounts", List(ValidationError("error.path.missing"))),
        (__ \ "transactions", List(ValidationError("error.path.missing"))),
        (__ \ "created", List(ValidationError("error.path.missing"))),
        (__ \ "expires", List(ValidationError("error.path.missing")))
      ))
    )
  )

  describe("A Zone") {
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    describe("without a name or metadata") {
      implicit val zone = Zone(
        ZoneId(
          UUID.fromString("b0c608d4-22f5-460e-8872-15a10d79daf2")
        ),
        AccountId(0),
        Map(
          MemberId(0) ->
            Member(MemberId(0), PublicKey(publicKeyBytes), Some("Banker")),
          MemberId(1) ->
            Member(MemberId(1), PublicKey(publicKeyBytes), Some("Dave"))
        ),
        Map(
          AccountId(0) ->
            Account(AccountId(0), Set(MemberId(0)), Some("Bank")),
          AccountId(1) ->
            Account(AccountId(1), Set(MemberId(1)), Some("Dave's account"))
        ),
        Map(
          TransactionId(0) ->
            Transaction(
              TransactionId(0),
              AccountId(0),
              AccountId(1),
              BigDecimal(1000000),
              MemberId(0),
              1433611420487L,
              Some("Dave's lottery win")
            )
        ),
        1433611420487L,
        1433611420487L
      )
      implicit val zoneJson = Json.parse(
        s"""
           |{
           |  "id":"b0c608d4-22f5-460e-8872-15a10d79daf2",
           |  "equityAccountId":0,
           |  "members":[
           |    {"id":0,"ownerPublicKey":"${ByteString.of(publicKeyBytes: _*).base64}","name":"Banker"},
           |    {"id":1,"ownerPublicKey":"${ByteString.of(publicKeyBytes: _*).base64}","name":"Dave"}
           |  ],
           |  "accounts":[
           |    {"id":0,"ownerMemberIds":[0],"name":"Bank"},
           |    {"id":1,"ownerMemberIds":[1],"name":"Dave's account"}
           |  ],
           |  "transactions":[
           |    {
           |      "id":0,
           |      "from":0,
           |      "to":1,
           |      "value":1000000,
           |      "creator":0,
           |      "created":1433611420487,
           |      "description":"Dave's lottery win"
           |    }
           |  ],
           |  "created":1433611420487,
           |  "expires":1433611420487
           |}""".stripMargin
      )
      it should behave like read
      it should behave like write
    }
    describe("with a name and metadata") {
      implicit val zone = Zone(
        ZoneId(
          UUID.fromString("b0c608d4-22f5-460e-8872-15a10d79daf2")
        ),
        AccountId(0),
        Map(
          MemberId(0) ->
            Member(MemberId(0), PublicKey(publicKeyBytes), Some("Banker")),
          MemberId(1) ->
            Member(MemberId(1), PublicKey(publicKeyBytes), Some("Dave"))
        ),
        Map(
          AccountId(0) ->
            Account(AccountId(0), Set(MemberId(0)), Some("Bank")),
          AccountId(1) ->
            Account(AccountId(1), Set(MemberId(1)), Some("Dave's account"))
        ),
        Map(
          TransactionId(0) ->
            Transaction(
              TransactionId(0),
              AccountId(0),
              AccountId(1),
              BigDecimal(1000000),
              MemberId(0),
              1433611420487L,
              Some("Dave's lottery win")
            )
        ),
        1433611420487L,
        1433611420487L,
        Some("Dave's zone"),
        Some(
          Json.obj(
            "currency" -> "GBP"
          )
        )
      )
      implicit val zoneJson = Json.parse(
        s"""
           |{
           |  "id":"b0c608d4-22f5-460e-8872-15a10d79daf2",
           |  "equityAccountId":0,
           |  "members":[
           |    {"id":0,"ownerPublicKey":"${ByteString.of(publicKeyBytes: _*).base64}","name":"Banker"},
           |    {"id":1,"ownerPublicKey":"${ByteString.of(publicKeyBytes: _*).base64}","name":"Dave"}
           |  ],
           |  "accounts":[
           |    {"id":0,"ownerMemberIds":[0],"name":"Bank"},
           |    {"id":1,"ownerMemberIds":[1],"name":"Dave's account"}
           |  ],
           |  "transactions":[
           |    {
           |      "id":0,
           |      "from":0,
           |      "to":1,
           |      "value":1000000,
           |      "creator":0,
           |      "created":1433611420487,
           |      "description":"Dave's lottery win"
           |    }
           |  ],
           |  "created":1433611420487,
           |  "expires":1433611420487,
           |  "name":"Dave's zone",
           |  "metadata":{"currency":"GBP"}
           |}""".stripMargin
      )
      it should behave like read
      it should behave like write
    }
  }
}
