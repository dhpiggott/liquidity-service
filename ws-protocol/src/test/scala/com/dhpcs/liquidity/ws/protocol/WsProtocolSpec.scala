package com.dhpcs.liquidity.ws.protocol

import java.security.KeyPairGenerator
import java.util.UUID

import com.dhpcs.jsonrpc.JsonRpcMessage.{ArrayParams, CorrelationId, NumericCorrelationId, ObjectParams}
import com.dhpcs.jsonrpc._
import com.dhpcs.liquidity.model._
import okio.ByteString
import org.scalatest.{FunSpec, Matchers}
import play.api.libs.json._

class WsProtocolSpec extends FunSpec with Matchers {

  describe("A Command") {
    describe("with an invalid method")(
      it should behave like commandReadError(
        JsonRpcRequestMessage(
          "invalidMethod",
          ObjectParams(
            Json.obj()
          ),
          NumericCorrelationId(1)
        ),
        JsError("unknown method invalidMethod")
      )
    )
    describe("of type CreateZoneCommand with metadata") {
      describe("with params of the wrong type")(
        it should behave like commandReadError(
          JsonRpcRequestMessage(
            "createZone",
            ArrayParams(
              Json.arr()
            ),
            NumericCorrelationId(1)
          ),
          JsError(__, "command parameters must be named")
        )
      )
      describe("with empty params")(
        it should behave like commandReadError(
          JsonRpcRequestMessage(
            "createZone",
            ObjectParams(
              Json.obj()
            ),
            NumericCorrelationId(1)
          ),
          JsError(__ \ "equityOwnerPublicKey", "error.path.missing")
        )
      )
      val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
      implicit val createZoneCommand = CreateZoneCommand(
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
      implicit val id = NumericCorrelationId(1)
      implicit val jsonRpcRequestMessage = JsonRpcRequestMessage(
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
      it should behave like commandRead
      it should behave like commandWrite
    }
    describe("of type AddTransactionCommand")(
      describe("with a negative value")(
        it should behave like commandReadError(
          JsonRpcRequestMessage(
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
          ),
          JsError(__ \ "value", JsonValidationError("error.min", 0))
        )
      )
    )
  }

  describe("A Response") {
    describe("of type CreateZoneResponse")(
      describe("with empty params")(
        it should behave like responseReadError(
          JsonRpcResponseSuccessMessage(
            Json.obj(),
            NumericCorrelationId(0)
          ),
          "createZone",
          JsError(__ \ "zone", "error.path.missing")
        )
      )
    )
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    implicit val createZoneResponse = CreateZoneResponse(
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
    implicit val id = NumericCorrelationId(1)
    implicit val jsonRpcResponseSuccessMessage = JsonRpcResponseSuccessMessage(
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
    implicit val method = "createZone"
    it should behave like responseRead
    it should behave like responseWrite
  }

  describe("A Notification") {
    describe("with an invalid method")(
      it should behave like notificationReadError(
        JsonRpcNotificationMessage(
          "invalidMethod",
          ObjectParams(
            Json.obj()
          )
        ),
        JsError("unknown method invalidMethod")
      )
    )
    describe("of type ClientJoinedZoneNotification") {
      describe("with params of the wrong type")(
        it should behave like notificationReadError(
          JsonRpcNotificationMessage(
            "clientJoinedZone",
            ArrayParams(
              Json.arr()
            )
          ),
          JsError(__, "notification parameters must be named")
        )
      )
      describe("with empty params")(
        it should behave like notificationReadError(
          JsonRpcNotificationMessage(
            "clientJoinedZone",
            ObjectParams(
              Json.obj()
            )
          ),
          JsError(
            Seq(
              (__ \ "publicKey", Seq(JsonValidationError("error.path.missing"))),
              (__ \ "zoneId", Seq(JsonValidationError("error.path.missing")))
            )
          )
        )
      )
      val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
      implicit val clientJoinedZoneNotification = ClientJoinedZoneNotification(
        ZoneId(UUID.fromString("a52e984e-f0aa-4481-802b-74622cb3f6f6")),
        PublicKey(publicKeyBytes)
      )
      implicit val jsonRpcNotificationMessage = JsonRpcNotificationMessage(
        "clientJoinedZone",
        ObjectParams(
          Json.obj(
            "zoneId"    -> "a52e984e-f0aa-4481-802b-74622cb3f6f6",
            "publicKey" -> ByteString.of(publicKeyBytes: _*).base64
          )
        )
      )
      it should behave like notificationRead
      it should behave like notificationWrite
    }
  }

  private[this] def commandReadError(jsonRpcRequestMessage: JsonRpcRequestMessage, jsError: JsError) =
    it(s"should fail to decode with error $jsError")(
      Command.read(jsonRpcRequestMessage) should equal(jsError)
    )

  private[this] def commandRead(implicit jsonRpcRequestMessage: JsonRpcRequestMessage, command: Command) =
    it(s"should decode to $command")(
      Command.read(jsonRpcRequestMessage) should be(JsSuccess(command))
    )

  private[this] def commandWrite(implicit command: Command,
                                 id: CorrelationId,
                                 jsonRpcRequestMessage: JsonRpcRequestMessage) =
    it(s"should encode to $jsonRpcRequestMessage")(
      Command.write(command, id) should be(jsonRpcRequestMessage)
    )

  private[this] def responseReadError(jsonRpcResponseSuccessMessage: JsonRpcResponseSuccessMessage,
                                      method: String,
                                      jsError: JsError) =
    it(s"should fail to decode with error $jsError")(
      Response.read(jsonRpcResponseSuccessMessage, method) should equal(jsError)
    )

  private[this] def responseRead(implicit jsonRpcResponseSuccessMessage: JsonRpcResponseSuccessMessage,
                                 method: String,
                                 response: Response) =
    it(s"should decode to $response")(
      Response.read(jsonRpcResponseSuccessMessage, method) should be(JsSuccess(response))
    )

  private[this] def responseWrite(implicit response: Response,
                                  id: CorrelationId,
                                  jsonRpcResponseSuccessMessage: JsonRpcResponseSuccessMessage) =
    it(s"should encode to $jsonRpcResponseSuccessMessage")(
      Response.write(response, id) should be(jsonRpcResponseSuccessMessage)
    )

  private[this] def notificationReadError(jsonRpcNotificationMessage: JsonRpcNotificationMessage, jsError: JsError) =
    it(s"should fail to decode with error $jsError")(
      Notification.read(jsonRpcNotificationMessage) should equal(jsError)
    )

  private[this] def notificationRead(implicit jsonRpcNotificationMessage: JsonRpcNotificationMessage,
                                     notification: Notification) =
    it(s"should decode to $notification")(
      Notification.read(jsonRpcNotificationMessage) should be(JsSuccess(notification))
    )

  private[this] def notificationWrite(implicit notification: Notification,
                                      jsonRpcNotificationMessage: JsonRpcNotificationMessage) =
    it(s"should encode to $jsonRpcNotificationMessage")(
      Notification.write(notification) should be(jsonRpcNotificationMessage)
    )

}
