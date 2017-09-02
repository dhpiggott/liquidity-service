package com.dhpcs.liquidity.ws.protocol.legacy

import java.security.KeyPairGenerator
import java.util.UUID

import com.dhpcs.jsonrpc.JsonRpcMessage.{ArrayParams, NumericCorrelationId, ObjectParams}
import com.dhpcs.jsonrpc._
import com.dhpcs.liquidity.model._
import okio.ByteString
import org.scalatest.FreeSpec
import play.api.libs.json._

import scala.collection.immutable.Seq

class LegacyWsProtocolSpec extends FreeSpec {

  "A Command" - {
    "with an invalid method" - {
      val jsonRpcRequestMessage = JsonRpcRequestMessage(
        "invalidMethod",
        ObjectParams(
          JsObject.empty
        ),
        NumericCorrelationId(1)
      )
      val jsError = JsError("unknown method invalidMethod")
      s"will fail to decode with error $jsError" in assert(
        LegacyWsProtocol.Command.read(jsonRpcRequestMessage) === jsError
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
          LegacyWsProtocol.Command.read(jsonRpcRequestMessage) === jsError
        )
      }
      "with empty params" - {
        val jsonRpcRequestMessage = JsonRpcRequestMessage(
          "createZone",
          ObjectParams(
            JsObject.empty
          ),
          NumericCorrelationId(1)
        )
        val jsError = JsError(__ \ "equityOwnerPublicKey", "error.path.missing")
        s"will fail to decode with error $jsError" in assert(
          LegacyWsProtocol.Command.read(jsonRpcRequestMessage) === jsError
        )
      }
      val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
      val createZoneCommand = LegacyWsProtocol.CreateZoneCommand(
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
        LegacyWsProtocol.Command.read(jsonRpcRequestMessage) === JsSuccess(createZoneCommand)
      )
      s"will encode to $jsonRpcRequestMessage" in assert(
        LegacyWsProtocol.Command.write(createZoneCommand, id) === jsonRpcRequestMessage
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
          LegacyWsProtocol.Command.read(jsonRpcRequestMessage) === jsError
        )
      }
    )
  }

  "A Response" - {
    "of type CreateZoneResponse" - (
      "with empty params" - {
        val jsonRpcResponseSuccessMessage = JsonRpcResponseSuccessMessage(
          JsObject.empty,
          NumericCorrelationId(0)
        )
        val method  = "createZone"
        val jsError = JsError(__ \ "zone", "error.path.missing")
        s"will fail to decode with error $jsError" in assert(
          LegacyWsProtocol.SuccessResponse.read(jsonRpcResponseSuccessMessage, method) === jsError
        )
      }
    )
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    val createZoneResponse = LegacyWsProtocol.CreateZoneResponse(
      Zone(
        ZoneId(UUID.fromString("158842d1-38c7-4ad3-ab83-d4c723c9aaf3")),
        AccountId(0),
        Map(
          MemberId(0) ->
            Member(MemberId(0), Set(PublicKey(publicKeyBytes)), Some("Banker"))
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
      LegacyWsProtocol.SuccessResponse.read(jsonRpcResponseSuccessMessage, method) === JsSuccess(createZoneResponse)
    )
    s"will encode to $jsonRpcResponseSuccessMessage" in assert(
      LegacyWsProtocol.SuccessResponse.write(createZoneResponse, id) === jsonRpcResponseSuccessMessage
    )
  }

  "A Notification" - {
    "with an invalid method" - {
      val jsonRpcNotificationMessage = JsonRpcNotificationMessage(
        "invalidMethod",
        ObjectParams(
          JsObject.empty
        )
      )
      val jsError = JsError("unknown method invalidMethod")
      s"will fail to decode with error $jsError" in assert(
        LegacyWsProtocol.Notification.read(jsonRpcNotificationMessage) === jsError
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
          LegacyWsProtocol.Notification.read(jsonRpcNotificationMessage) === jsError
        )
      }
      "with empty params" - {
        val jsonRpcNotificationMessage = JsonRpcNotificationMessage(
          "clientJoinedZone",
          ObjectParams(
            JsObject.empty
          )
        )
        val jsError = JsError(
          Seq(
            (__ \ "publicKey", Seq(JsonValidationError("error.path.missing"))),
            (__ \ "zoneId", Seq(JsonValidationError("error.path.missing")))
          )
        )
        s"will fail to decode with error $jsError" in assert(
          LegacyWsProtocol.Notification.read(jsonRpcNotificationMessage) === jsError
        )
      }
      val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
      val clientJoinedZoneNotification = LegacyWsProtocol.ClientJoinedZoneNotification(
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
        LegacyWsProtocol.Notification.read(jsonRpcNotificationMessage) === JsSuccess(clientJoinedZoneNotification)
      )
      s"will encode to $jsonRpcNotificationMessage" in assert(
        LegacyWsProtocol.Notification.write(clientJoinedZoneNotification) === jsonRpcNotificationMessage
      )
    }
  }
}
