package com.dhpcs.liquidity.model

import java.security.KeyPairGenerator
import java.util.UUID

import com.dhpcs.json.FormatBehaviors
import com.dhpcs.liquidity.model.ValueFormatSpec.TestValue
import okio.ByteString
import org.scalatest.{FunSpec, Matchers}
import play.api.libs.json._

object ValueFormatSpec {

  final case class TestValue(value: String)

  object TestValue {
    implicit final val TestFormat: Format[TestValue] = ValueFormat[TestValue, String](TestValue(_), _.value)
  }

}

class ValueFormatSpec extends FunSpec with FormatBehaviors[TestValue] with Matchers {

  describe("A JsValue of the wrong type")(
    it should behave like readError(
      Json.parse(
        """
          |0""".stripMargin
      ),
      JsError(__, "error.expected.jsstring")
    )
  )

  describe("A TestValue") {
    implicit val testValue = TestValue("test")
    implicit val testValueJson = Json.parse(
      """
        |"test"""".stripMargin
    )
    it should behave like read
    it should behave like write
  }
}

class PublicKeySpec extends FunSpec with FormatBehaviors[PublicKey] with Matchers {

  describe("A JsValue of the wrong type")(
    it should behave like readError(
      Json.parse("""
                   |0""".stripMargin),
      JsError(__, "error.expected.jsstring")
    )
  )

  describe("A PublicKey") {
    val publicKeyBytes     = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    implicit val publicKey = PublicKey(publicKeyBytes)
    implicit val publicKeyJson = Json.parse(
      s"""
         |"${ByteString.of(publicKeyBytes: _*).base64}"""".stripMargin
    )
    it should behave like read
    it should behave like write
  }
}

class MemberSpec extends FunSpec with FormatBehaviors[Member] with Matchers {

  describe("A JsValue of the wrong type")(
    it should behave like readError(
      Json.parse(
        """
          |0""".stripMargin
      ),
      JsError(__, "error.expected.jsobject")
    )
  )

  describe("A Member") {
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    describe("without a name or metadata") {
      implicit val member = Member(MemberId(0), PublicKey(publicKeyBytes))
      implicit val memberJson = Json.parse(
        s"""
           |{
           |  "id":0,
           |  "ownerPublicKey":"${ByteString.of(publicKeyBytes: _*).base64}"
           |}""".stripMargin
      )
      it should behave like read
      it should behave like write
    }
    describe("with a name and metadata") {
      implicit val member = Member(
        MemberId(0),
        PublicKey(publicKeyBytes),
        Some("Dave"),
        Some(
          Json.obj(
            "color" -> "0x0000FF"
          )
        )
      )
      implicit val memberJson = Json.parse(
        s"""
           |{
           |  "id":0,
           |  "ownerPublicKey":"${ByteString.of(publicKeyBytes: _*).base64}",
           |  "name":"Dave",
           |  "metadata":{"color":"0x0000FF"}
           |}""".stripMargin
      )
      it should behave like read
      it should behave like write
    }
  }
}

class AccountSpec extends FunSpec with FormatBehaviors[Account] with Matchers {

  describe("A JsValue of the wrong type")(
    it should behave like readError(
      Json.parse(
        """
          |0""".stripMargin
      ),
      JsError(__, "error.expected.jsobject")
    )
  )

  describe("An Account") {
    describe("without a name or metadata") {
      implicit val account = Account(
        AccountId(0),
        Set(MemberId(0))
      )
      implicit val accountJson = Json.parse(
        """
          |{
          |  "id":0,
          |  "ownerMemberIds":[0]
          |}""".stripMargin
      )
      it should behave like read
      it should behave like write
    }
    describe("with a name and metadata") {
      implicit val account = Account(
        AccountId(0),
        Set(MemberId(0)),
        Some("Dave's account"),
        Some(
          Json.obj(
            "hidden" -> true
          )
        )
      )
      implicit val accountJson = Json.parse(
        """
          |{
          |  "id":0,
          |  "ownerMemberIds":[0],
          |  "name":"Dave's account",
          |  "metadata":{"hidden":true}
          |}""".stripMargin
      )
      it should behave like read
      it should behave like write
    }
  }
}

class TransactionSpec extends FunSpec with FormatBehaviors[Transaction] with Matchers {

  describe("A JsValue of the wrong type")(
    it should behave like readError(
      Json.parse(
        """
          |0""".stripMargin
      ),
      JsError(
        Seq(
          (__ \ "id", Seq(JsonValidationError("error.path.missing"))),
          (__ \ "from", Seq(JsonValidationError("error.path.missing"))),
          (__ \ "to", Seq(JsonValidationError("error.path.missing"))),
          (__ \ "value", Seq(JsonValidationError("error.path.missing"))),
          (__ \ "creator", Seq(JsonValidationError("error.path.missing"))),
          (__ \ "created", Seq(JsonValidationError("error.path.missing")))
        ))
    )
  )

  describe("A Transaction") {
    describe("without a description or metadata") {
      implicit val transaction = Transaction(
        TransactionId(0),
        AccountId(0),
        AccountId(1),
        BigDecimal(1000000),
        MemberId(2),
        1434115187612L
      )
      implicit val transactionJson = Json.parse(
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
      it should behave like read
      it should behave like write
    }
    describe("with a description and metadata") {
      implicit val transaction = Transaction(
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
      implicit val transactionJson = Json.parse(
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
      it should behave like read
      it should behave like write
    }
  }
}

class ZoneSpec extends FunSpec with FormatBehaviors[Zone] with Matchers {

  describe("A JsValue of the wrong type")(
    it should behave like readError(
      Json.parse(
        """
          |0""".stripMargin
      ),
      JsError(
        Seq(
          (__ \ "id", Seq(JsonValidationError("error.path.missing"))),
          (__ \ "equityAccountId", Seq(JsonValidationError("error.path.missing"))),
          (__ \ "members", Seq(JsonValidationError("error.path.missing"))),
          (__ \ "accounts", Seq(JsonValidationError("error.path.missing"))),
          (__ \ "transactions", Seq(JsonValidationError("error.path.missing"))),
          (__ \ "created", Seq(JsonValidationError("error.path.missing"))),
          (__ \ "expires", Seq(JsonValidationError("error.path.missing")))
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
