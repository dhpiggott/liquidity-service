package com.dhpcs.liquidity.ws.protocol

import java.security.KeyPairGenerator
import java.util.UUID

import com.dhpcs.jsonrpc.JsonRpcMessage.{ArrayParams, NumericCorrelationId, ObjectParams}
import com.dhpcs.jsonrpc._
import com.dhpcs.liquidity.model._
import okio.ByteString
import org.scalatest.{FreeSpec, Matchers}
import play.api.libs.json._

import scala.collection.immutable.Seq

class WsProtocolSpec extends FreeSpec with Matchers {

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
      s"should fail to decode with error $jsError" in (
        Command.read(jsonRpcRequestMessage) should equal(jsError)
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
        s"should fail to decode with error $jsError" in (
          Command.read(jsonRpcRequestMessage) should equal(jsError)
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
        s"should fail to decode with error $jsError" in (
          Command.read(jsonRpcRequestMessage) should equal(jsError)
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
          Json.obj(
            "currency" -> "GBP"
          )
        )
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
      s"should decode to $createZoneCommand" in (
        Command.read(jsonRpcRequestMessage) should be(JsSuccess(createZoneCommand))
      )
      s"should encode to $jsonRpcRequestMessage" in (
        Command.write(createZoneCommand, id) should be(jsonRpcRequestMessage)
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
        s"should fail to decode with error $jsError" in (
          Command.read(jsonRpcRequestMessage) should equal(jsError)
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
        s"should fail to decode with error $jsError" in (
          Response.read(jsonRpcResponseSuccessMessage, method) should equal(jsError)
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
    s"should decode to $createZoneResponse" in (
      Response.read(jsonRpcResponseSuccessMessage, method) should be(JsSuccess(createZoneResponse))
    )
    s"should encode to $jsonRpcResponseSuccessMessage" in (
      Response.write(createZoneResponse, id) should be(jsonRpcResponseSuccessMessage)
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
      s"should fail to decode with error $jsError" in (
        Notification.read(jsonRpcNotificationMessage) should equal(jsError)
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
        s"should fail to decode with error $jsError" in (
          Notification.read(jsonRpcNotificationMessage) should equal(jsError)
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
        s"should fail to decode with error $jsError" in (
          Notification.read(jsonRpcNotificationMessage) should equal(jsError)
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
      s"should decode to $clientJoinedZoneNotification" in (
        Notification.read(jsonRpcNotificationMessage) should be(JsSuccess(clientJoinedZoneNotification))
      )
      s"should encode to $jsonRpcNotificationMessage" in (
        Notification.write(clientJoinedZoneNotification) should be(jsonRpcNotificationMessage)
      )
    }
  }
}
