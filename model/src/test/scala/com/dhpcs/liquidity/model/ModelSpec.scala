package com.dhpcs.liquidity.model

import java.security.KeyPairGenerator
import java.util.UUID

import com.dhpcs.liquidity.model.ValueFormatSpec.TestValue
import okio.ByteString
import org.scalatest.FreeSpec
import play.api.libs.json._

object ValueFormatSpec {

  final case class TestValue(value: String)

  object TestValue {
    implicit final val TestFormat: Format[TestValue] = ValueFormat[TestValue, String](TestValue(_), _.value)
  }

}

class ValueFormatSpec extends FreeSpec {

  "A JsValue of the wrong type" - {
    val json    = Json.parse("0")
    val jsError = JsError(__, "error.expected.jsstring")
    s"will fail to decode with error $jsError" in assert(
      Json.fromJson[TestValue](json) === jsError
    )
  }

  "A TestValue" - {
    val testValue = TestValue("test")
    val testValueJson = Json.parse(
      """
        |"test"""".stripMargin
    )
    s"will decode to $testValue" in assert(
      Json.fromJson[TestValue](testValueJson) === JsSuccess(testValue)
    )
    s"will encode to $testValueJson" in assert(
      Json.toJson(testValue) === testValueJson
    )
  }
}

class PublicKeySpec extends FreeSpec {

  "A JsValue of the wrong type" - {
    val json    = Json.parse("0")
    val jsError = JsError(__, "error.expected.jsstring")
    s"will fail to decode with error $jsError" in assert(
      Json.fromJson[PublicKey](json) === jsError
    )
  }

  "A PublicKey" - {
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    val publicKey      = PublicKey(publicKeyBytes)
    val publicKeyJson = Json.parse(
      s"""
         |"${ByteString.of(publicKeyBytes: _*).base64}"""".stripMargin
    )
    s"will decode to $publicKey" in assert(
      Json.fromJson[PublicKey](publicKeyJson) === JsSuccess(publicKey)
    )
    s"will encode to $publicKeyJson" in assert(
      Json.toJson(publicKey) === publicKeyJson
    )
  }
}

class MemberSpec extends FreeSpec {

  "A JsValue of the wrong type" - {
    val json    = Json.parse("0")
    val jsError = JsError(__, "error.expected.jsobject")
    s"will fail to decode with error $jsError" in assert(
      Json.fromJson[Member](json) === jsError
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
      s"will decode to $member" in assert(
        Json.fromJson[Member](memberJson) === JsSuccess(member)
      )
      s"will encode to $memberJson" in assert(
        Json.toJson(member) === memberJson
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
      s"will decode to $member" in assert(
        Json.fromJson[Member](memberJson) === JsSuccess(member)
      )
      s"will encode to $memberJson" in assert(
        Json.toJson(member) === memberJson
      )
    }
  }
}

class AccountSpec extends FreeSpec {

  "A JsValue of the wrong type" - {
    val json    = Json.parse("0")
    val jsError = JsError(__, "error.expected.jsobject")
    s"will fail to decode with error $jsError" in assert(
      Json.fromJson[Account](json) === jsError
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
      s"will decode to $account" in assert(
        Json.fromJson[Account](accountJson) === JsSuccess(account)
      )
      s"will encode to $accountJson" in assert(
        Json.toJson(account) === accountJson
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
      s"will decode to $account" in assert(
        Json.fromJson[Account](accountJson) === JsSuccess(account)
      )
      s"will encode to $accountJson" in assert(
        Json.toJson(account) === accountJson
      )
    }
  }
}

class TransactionSpec extends FreeSpec {

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
    s"will fail to decode with error $jsError" in assert(
      Json.fromJson[Transaction](json) === jsError
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
      s"will decode to $transaction" in assert(
        Json.fromJson[Transaction](transactionJson) === JsSuccess(transaction)
      )
      s"will encode to $transactionJson" in assert(
        Json.toJson(transaction) === transactionJson
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
      s"will decode to $transaction" in assert(
        Json.fromJson[Transaction](transactionJson) === JsSuccess(transaction)
      )
      s"will encode to $transactionJson" in assert(
        Json.toJson(transaction) === transactionJson
      )
    }
  }
}

class ZoneSpec extends FreeSpec {

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
    s"will fail to decode with error $jsError" in assert(
      Json.fromJson[Zone](json) === jsError
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
      s"will decode to $zone" in assert(
        Json.fromJson[Zone](zoneJson) === JsSuccess(zone)
      )
      s"will encode to $zoneJson" in assert(
        Json.toJson(zone) === zoneJson
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
      s"will decode to $zone" in assert(
        Json.fromJson[Zone](zoneJson) === JsSuccess(zone)
      )
      s"will encode to $zoneJson" in assert(
        Json.toJson(zone) === zoneJson
      )
    }
  }
}
