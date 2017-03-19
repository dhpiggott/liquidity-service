package com.dhpcs.liquidity.model

import java.security.KeyPairGenerator
import java.util.UUID

import com.dhpcs.liquidity.model.ValueFormatSpec.TestValue
import okio.ByteString
import org.scalatest.{FreeSpec, Matchers}
import play.api.libs.json._

object ValueFormatSpec {

  final case class TestValue(value: String)

  object TestValue {
    implicit final val TestFormat: Format[TestValue] = ValueFormat(TestValue(_), _.value)
  }

}

class ValueFormatSpec extends FreeSpec with Matchers {

  "A JsValue of the wrong type" - {
    val json    = Json.parse("0")
    val jsError = JsError(__, "error.expected.jsstring")
    s"should fail to decode with error $jsError" in (
      Json.fromJson[TestValue](json) shouldBe jsError
    )
  }

  "A TestValue" - {
    val testValue = TestValue("test")
    val testValueJson = Json.parse(
      """
        |"test"""".stripMargin
    )
    s"should decode to $testValue" in (
      Json.fromJson[TestValue](testValueJson) shouldBe JsSuccess(testValue)
    )
    s"should encode to $testValueJson" in (
      Json.toJson(testValue) shouldBe testValueJson
    )
  }
}

class PublicKeySpec extends FreeSpec with Matchers {

  "A JsValue of the wrong type" - {
    val json    = Json.parse("0")
    val jsError = JsError(__, "error.expected.jsstring")
    s"should fail to decode with error $jsError" in (
      Json.fromJson[PublicKey](json) shouldBe jsError
    )
  }

  "A PublicKey" - {
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    val publicKey      = PublicKey(publicKeyBytes)
    val publicKeyJson = Json.parse(
      s"""
         |"${ByteString.of(publicKeyBytes: _*).base64}"""".stripMargin
    )
    s"should decode to $publicKey" in (
      Json.fromJson[PublicKey](publicKeyJson) shouldBe JsSuccess(publicKey)
    )
    s"should encode to $publicKeyJson" in (
      Json.toJson(publicKey) shouldBe publicKeyJson
    )
  }
}

class MemberSpec extends FreeSpec with Matchers {

  "A JsValue of the wrong type" - {
    val json    = Json.parse("0")
    val jsError = JsError(__, "error.expected.jsobject")
    s"should fail to decode with error $jsError" in (
      Json.fromJson[Member](json) shouldBe jsError
    )
  }

  "A Member" - {
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    "without a name or metadata" - {
      val member = Member(MemberId(0), PublicKey(publicKeyBytes))
      val memberJson = Json.parse(
        s"""
           |{
           |  "id":0,
           |  "ownerPublicKey":"${ByteString.of(publicKeyBytes: _*).base64}"
           |}""".stripMargin
      )
      s"should decode to $member" in (
        Json.fromJson[Member](memberJson) shouldBe JsSuccess(member)
      )
      s"should encode to $memberJson" in (
        Json.toJson(member) shouldBe memberJson
      )
    }
    "with a name and metadata" - {
      val member = Member(
        MemberId(0),
        PublicKey(publicKeyBytes),
        Some("Dave"),
        Some(
          Json.obj(
            "color" -> "0x0000FF"
          )
        )
      )
      val memberJson = Json.parse(
        s"""
           |{
           |  "id":0,
           |  "ownerPublicKey":"${ByteString.of(publicKeyBytes: _*).base64}",
           |  "name":"Dave",
           |  "metadata":{"color":"0x0000FF"}
           |}""".stripMargin
      )
      s"should decode to $member" in (
        Json.fromJson[Member](memberJson) shouldBe JsSuccess(member)
      )
      s"should encode to $memberJson" in (
        Json.toJson(member) shouldBe memberJson
      )
    }
  }
}

class AccountSpec extends FreeSpec with Matchers {

  "A JsValue of the wrong type" - {
    val json    = Json.parse("0")
    val jsError = JsError(__, "error.expected.jsobject")
    s"should fail to decode with error $jsError" in (
      Json.fromJson[Account](json) shouldBe jsError
    )
  }

  "An Account" - {
    "without a name or metadata" - {
      val account = Account(
        AccountId(0),
        Set(MemberId(0))
      )
      val accountJson = Json.parse(
        """
          |{
          |  "id":0,
          |  "ownerMemberIds":[0]
          |}""".stripMargin
      )
      s"should decode to $account" in (
        Json.fromJson[Account](accountJson) shouldBe JsSuccess(account)
      )
      s"should encode to $accountJson" in (
        Json.toJson(account) shouldBe accountJson
      )
    }
    "with a name and metadata" - {
      val account = Account(
        AccountId(0),
        Set(MemberId(0)),
        Some("Dave's account"),
        Some(
          Json.obj(
            "hidden" -> true
          )
        )
      )
      val accountJson = Json.parse(
        """
          |{
          |  "id":0,
          |  "ownerMemberIds":[0],
          |  "name":"Dave's account",
          |  "metadata":{"hidden":true}
          |}""".stripMargin
      )
      s"should decode to $account" in (
        Json.fromJson[Account](accountJson) shouldBe JsSuccess(account)
      )
      s"should encode to $accountJson" in (
        Json.toJson(account) shouldBe accountJson
      )
    }
  }
}

class TransactionSpec extends FreeSpec with Matchers {

  "A JsValue of the wrong type" - {
    val json = Json.parse("0")
    val jsError = JsError(
      Seq(
        (__ \ "value", Seq(JsonValidationError("error.path.missing"))),
        (__ \ "from", Seq(JsonValidationError("error.path.missing"))),
        (__ \ "id", Seq(JsonValidationError("error.path.missing"))),
        (__ \ "created", Seq(JsonValidationError("error.path.missing"))),
        (__ \ "creator", Seq(JsonValidationError("error.path.missing"))),
        (__ \ "to", Seq(JsonValidationError("error.path.missing")))
      ))
    s"should fail to decode with error $jsError" in (
      Json.fromJson[Transaction](json) shouldBe jsError
    )
  }

  "A Transaction" - {
    "without a description or metadata" - {
      val transaction = Transaction(
        TransactionId(0),
        AccountId(0),
        AccountId(1),
        BigDecimal(1000000),
        MemberId(2),
        1434115187612L
      )
      val transactionJson = Json.parse(
        """
          |{
          |  "id":0,
          |  "from":0,
          |  "to":1,
          |  "value":1000000,
          |  "creator":2,
          |  "created":1434115187612
          |}""".stripMargin
      )
      s"should decode to $transaction" in (
        Json.fromJson[Transaction](transactionJson) shouldBe JsSuccess(transaction)
      )
      s"should encode to $transactionJson" in (
        Json.toJson(transaction) shouldBe transactionJson
      )
    }
    "with a description and metadata" - {
      val transaction = Transaction(
        TransactionId(0),
        AccountId(0),
        AccountId(1),
        BigDecimal(1000000),
        MemberId(2),
        1434115187612L,
        Some("Property purchase"),
        Some(
          Json.obj(
            "property" -> "The TARDIS"
          )
        )
      )
      val transactionJson = Json.parse(
        """
          |{
          |  "id":0,
          |  "from":0,
          |  "to":1,
          |  "value":1000000,
          |  "creator":2,
          |  "created":1434115187612,
          |  "description":"Property purchase",
          |  "metadata":{"property":"The TARDIS"}
          |}""".stripMargin
      )
      s"should decode to $transaction" in (
        Json.fromJson[Transaction](transactionJson) shouldBe JsSuccess(transaction)
      )
      s"should encode to $transactionJson" in (
        Json.toJson(transaction) shouldBe transactionJson
      )
    }
  }
}

class ZoneSpec extends FreeSpec with Matchers {

  "A JsValue of the wrong type" - {
    val json = Json.parse("0")
    val jsError = JsError(
      Seq(
        (__ \ "expires", Seq(JsonValidationError("error.path.missing"))),
        (__ \ "equityAccountId", Seq(JsonValidationError("error.path.missing"))),
        (__ \ "id", Seq(JsonValidationError("error.path.missing"))),
        (__ \ "created", Seq(JsonValidationError("error.path.missing"))),
        (__ \ "transactions", Seq(JsonValidationError("error.path.missing"))),
        (__ \ "accounts", Seq(JsonValidationError("error.path.missing"))),
        (__ \ "members", Seq(JsonValidationError("error.path.missing")))
      ))
    s"should fail to decode with error $jsError" in (
      Json.fromJson[Zone](json) shouldBe jsError
    )
  }

  "A Zone" - {
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    "without a name or metadata" - {
      val zone = Zone(
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
      val zoneJson = Json.parse(
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
      s"should decode to $zone" in (
        Json.fromJson[Zone](zoneJson) shouldBe JsSuccess(zone)
      )
      s"should encode to $zoneJson" in (
        Json.toJson(zone) shouldBe zoneJson
      )
    }
    "with a name and metadata" - {
      val zone = Zone(
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
      val zoneJson = Json.parse(
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
      s"should decode to $zone" in (
        Json.fromJson[Zone](zoneJson) shouldBe JsSuccess(zone)
      )
      s"should encode to $zoneJson" in (
        Json.toJson(zone) shouldBe zoneJson
      )
    }
  }
}
