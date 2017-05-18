package com.dhpcs.liquidity.ws.protocol.legacy

import java.security.KeyPairGenerator
import java.util.UUID

import com.dhpcs.jsonrpc.JsonRpcMessage.{ArrayParams, NumericCorrelationId, ObjectParams}
import com.dhpcs.jsonrpc._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.ws.protocol.legacy.LegacyModelFormats._
import com.dhpcs.liquidity.ws.protocol.legacy.LegacyModelFormatsSpec.ValueFormatSpec.TestValue
import okio.ByteString
import org.scalatest.FreeSpec
import play.api.libs.json._

import scala.collection.immutable.Seq

object LegacyModelFormatsSpec {

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
          Some(com.google.protobuf.struct.Struct(Map(
            "color" -> com.google.protobuf.struct.Value(com.google.protobuf.struct.Value.Kind.StringValue("0x0000FF"))
          )))
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
            com.google.protobuf.struct.Struct(Map(
              "hidden" -> com.google.protobuf.struct.Value(com.google.protobuf.struct.Value.Kind.BoolValue(true))
            )))
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
            com.google.protobuf.struct.Struct(
              Map(
                "property" -> com.google.protobuf.struct
                  .Value(com.google.protobuf.struct.Value.Kind.StringValue("The TARDIS"))
              )))
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
            com.google.protobuf.struct.Struct(Map(
              "currency" -> com.google.protobuf.struct.Value(com.google.protobuf.struct.Value.Kind.StringValue("GBP"))
            )))
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
}

class LegacyWsProtocolSpec extends FreeSpec {

  "A Command" - {
    "with an invalid method" - {
      val jsonRpcRequestMessage = JsonRpcRequestMessage(
        "invalidMethod",
        ObjectParams(
          Json.obj()
        ),
        NumericCorrelationId(1)
      )
      val jsError = JsError("unknown method invalidMethod")
      s"will fail to decode with error $jsError" in assert(
        Command.read(jsonRpcRequestMessage) === jsError
      )
    }
    "of type CreateZoneCommand with metadata" - {
      "with params of the wrong type" - {
        val jsonRpcRequestMessage = JsonRpcRequestMessage(
          "createZone",
          ArrayParams(
            Json.arr()
          ),
          NumericCorrelationId(1)
        )
        val jsError = JsError(__, "command parameters must be named")
        s"will fail to decode with error $jsError" in assert(
          Command.read(jsonRpcRequestMessage) === jsError
        )
      }
      "with empty params" - {
        val jsonRpcRequestMessage = JsonRpcRequestMessage(
          "createZone",
          ObjectParams(
            Json.obj()
          ),
          NumericCorrelationId(1)
        )
        val jsError = JsError(__ \ "equityOwnerPublicKey", "error.path.missing")
        s"will fail to decode with error $jsError" in assert(
          Command.read(jsonRpcRequestMessage) === jsError
        )
      }
      val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
      val createZoneCommand = CreateZoneCommand(
        PublicKey(publicKeyBytes),
        Some("Banker"),
        None,
        Some("Bank"),
        None,
        Some("Dave's zone"),
        Some(
          com.google.protobuf.struct.Struct(Map(
            "currency" -> com.google.protobuf.struct.Value(com.google.protobuf.struct.Value.Kind.StringValue("GBP"))
          )))
      )
      val id = NumericCorrelationId(1)
      val jsonRpcRequestMessage = JsonRpcRequestMessage(
        "createZone",
        ObjectParams(
          Json.obj(
            "equityOwnerPublicKey" -> ByteString.of(publicKeyBytes: _*).base64,
            "equityOwnerName"      -> "Banker",
            "equityAccountName"    -> "Bank",
            "name"                 -> "Dave's zone",
            "metadata" -> Json.obj(
              "currency" -> "GBP"
            )
          )
        ),
        NumericCorrelationId(1)
      )
      s"will decode to $createZoneCommand" in assert(
        Command.read(jsonRpcRequestMessage) === JsSuccess(createZoneCommand)
      )
      s"will encode to $jsonRpcRequestMessage" in assert(
        Command.write(createZoneCommand, id) === jsonRpcRequestMessage
      )
    }
    "of type AddTransactionCommand" - (
      "with a negative value" - {
        val jsonRpcRequestMessage = JsonRpcRequestMessage(
          "addTransaction",
          ObjectParams(
            Json.obj(
              "zoneId"   -> "6b5f604d-f116-44f8-9807-d26324c81034",
              "actingAs" -> 0,
              "from"     -> 0,
              "to"       -> 1,
              "value"    -> -1
            )
          ),
          NumericCorrelationId(1)
        )
        val jsError = JsError(__ \ "value", JsonValidationError("error.min", 0))
        s"will fail to decode with error $jsError" in assert(
          Command.read(jsonRpcRequestMessage) === jsError
        )
      }
    )
  }

  "A Response" - {
    "of type CreateZoneResponse" - (
      "with empty params" - {
        val jsonRpcResponseSuccessMessage = JsonRpcResponseSuccessMessage(
          Json.obj(),
          NumericCorrelationId(0)
        )
        val method  = "createZone"
        val jsError = JsError(__ \ "zone", "error.path.missing")
        s"will fail to decode with error $jsError" in assert(
          SuccessResponse.read(jsonRpcResponseSuccessMessage, method) === jsError
        )
      }
    )
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    val createZoneResponse = CreateZoneResponse(
      Zone(
        ZoneId(UUID.fromString("158842d1-38c7-4ad3-ab83-d4c723c9aaf3")),
        AccountId(0),
        Map(
          MemberId(0) ->
            Member(MemberId(0), PublicKey(publicKeyBytes), Some("Banker"))
        ),
        Map(
          AccountId(0) ->
            Account(AccountId(0), Set(MemberId(0)), Some("Bank"))
        ),
        Map.empty,
        1436179968835L,
        1436179968835L,
        Some("Dave's zone")
      )
    )
    val id = NumericCorrelationId(1)
    val jsonRpcResponseSuccessMessage = JsonRpcResponseSuccessMessage(
      Json.obj(
        "zone" -> Json.parse(
          s"""
               |{
               |  "id":"158842d1-38c7-4ad3-ab83-d4c723c9aaf3",
               |  "equityAccountId":0,
               |  "members":[
               |    {"id":0,"ownerPublicKey":"${ByteString.of(publicKeyBytes: _*).base64}","name":"Banker"}
               |  ],
               |  "accounts":[
               |    {"id":0,"ownerMemberIds":[0],"name":"Bank"}
               |  ],
               |  "transactions":[],
               |  "created":1436179968835,
               |  "expires":1436179968835,
               |  "name":"Dave's zone"
               |}""".stripMargin
        )
      ),
      NumericCorrelationId(1)
    )
    val method = "createZone"
    s"will decode to $createZoneResponse" in assert(
      SuccessResponse.read(jsonRpcResponseSuccessMessage, method) === JsSuccess(createZoneResponse)
    )
    s"will encode to $jsonRpcResponseSuccessMessage" in assert(
      SuccessResponse.write(createZoneResponse, id) === jsonRpcResponseSuccessMessage
    )
  }

  "A Notification" - {
    "with an invalid method" - {
      val jsonRpcNotificationMessage = JsonRpcNotificationMessage(
        "invalidMethod",
        ObjectParams(
          Json.obj()
        )
      )
      val jsError = JsError("unknown method invalidMethod")
      s"will fail to decode with error $jsError" in assert(
        Notification.read(jsonRpcNotificationMessage) === jsError
      )
    }
    "of type ClientJoinedZoneNotification" - {
      "with params of the wrong type" - {
        val jsonRpcNotificationMessage = JsonRpcNotificationMessage(
          "clientJoinedZone",
          ArrayParams(
            Json.arr()
          )
        )
        val jsError = JsError(__, "notification parameters must be named")
        s"will fail to decode with error $jsError" in assert(
          Notification.read(jsonRpcNotificationMessage) === jsError
        )
      }
      "with empty params" - {
        val jsonRpcNotificationMessage = JsonRpcNotificationMessage(
          "clientJoinedZone",
          ObjectParams(
            Json.obj()
          )
        )
        val jsError = JsError(
          Seq(
            (__ \ "publicKey", Seq(JsonValidationError("error.path.missing"))),
            (__ \ "zoneId", Seq(JsonValidationError("error.path.missing")))
          )
        )
        s"will fail to decode with error $jsError" in assert(
          Notification.read(jsonRpcNotificationMessage) === jsError
        )
      }
      val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
      val clientJoinedZoneNotification = ClientJoinedZoneNotification(
        ZoneId(UUID.fromString("a52e984e-f0aa-4481-802b-74622cb3f6f6")),
        PublicKey(publicKeyBytes)
      )
      val jsonRpcNotificationMessage = JsonRpcNotificationMessage(
        "clientJoinedZone",
        ObjectParams(
          Json.obj(
            "zoneId"    -> "a52e984e-f0aa-4481-802b-74622cb3f6f6",
            "publicKey" -> ByteString.of(publicKeyBytes: _*).base64
          )
        )
      )
      s"will decode to $clientJoinedZoneNotification" in assert(
        Notification.read(jsonRpcNotificationMessage) === JsSuccess(clientJoinedZoneNotification)
      )
      s"will encode to $jsonRpcNotificationMessage" in assert(
        Notification.write(clientJoinedZoneNotification) === jsonRpcNotificationMessage
      )
    }
  }
}
