package com.dhpcs.liquidity.models

import java.security.KeyPairGenerator
import java.util.UUID

import com.dhpcs.json.JsResultUniformity
import com.dhpcs.jsonrpc._
import okio.ByteString
import org.scalatest.OptionValues._
import org.scalatest._
import play.api.data.validation.ValidationError
import play.api.libs.json._

class MessageSpec extends FunSpec with Matchers {

  describe("A Command") {
    describe("with an invalid method") {
      it should behave like commandReadError(
        JsonRpcRequestMessage(
          "invalidMethod",
          Right(Json.obj()),
          Some(Right(1))
        ),
        None
      )
    }
    describe("of type CreateZoneCommand with metadata") {
      describe("with params of the wrong type") {
        it should behave like commandReadError(
          JsonRpcRequestMessage(
            "createZone",
            Left(Json.arr()),
            Some(Right(1))
          ),
          Some(JsError(
            List(
              (__, List(ValidationError("command parameters must be named")))
            )
          ))
        )
      }
      describe("with empty params") {
        it should behave like commandReadError(
          JsonRpcRequestMessage(
            "createZone",
            Right(Json.obj()),
            Some(Right(1))
          ),
          Some(JsError(
            List(
              (__ \ "equityOwnerPublicKey", List(ValidationError("error.path.missing")))
            )
          ))
        )
      }
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
      implicit val id = Right(BigDecimal(1))
      implicit val jsonRpcRequestMessage = JsonRpcRequestMessage(
        "createZone",
        Right(
          Json.obj(
            "equityOwnerPublicKey" -> ByteString.of(publicKeyBytes: _*).base64,
            "equityOwnerName" -> "Banker",
            "equityAccountName" -> "Bank",
            "name" -> "Dave's zone",
            "metadata" -> Json.obj(
              "currency" -> "GBP"
            )
          )
        ),
        Some(Right(1))
      )
      it should behave like commandRead
      it should behave like commandWrite
    }
  }

  private def commandReadError(jsonRpcRequestMessage: JsonRpcRequestMessage, jsError: Option[JsError]) =
    it(s"should fail to decode with error $jsError") {
      val jsResult = Command.read(jsonRpcRequestMessage)
      jsError.fold(jsResult shouldBe empty)(
        jsResult.value should equal(_)(after being ordered[Command])
      )
    }

  private def commandRead(implicit jsonRpcRequestMessage: JsonRpcRequestMessage, command: Command) =
    it(s"should decode to $command") {
      Command.read(jsonRpcRequestMessage) should be(Some(JsSuccess(command)))
    }

  private def commandWrite(implicit command: Command,
                   id: Either[String, BigDecimal],
                   jsonRpcRequestMessage: JsonRpcRequestMessage) =
    it(s"should encode to $jsonRpcRequestMessage") {
      Command.write(command, Some(id)) should be(jsonRpcRequestMessage)
    }

  describe("A Response") {
    describe("of type CreateZoneResponse") {
      describe("with empty params") {
        it should behave like responseReadError(
          JsonRpcResponseMessage(
            Right(Json.obj()),
            Some(Right(0))
          ),
          "createZone",
          JsError(
            List(
              (__ \ "zone", List(ValidationError("error.path.missing")))
            )
          )
        )
      }
    }
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    implicit val createZoneResponse = Right(CreateZoneResponse(
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
    ))
    implicit val id = Right(BigDecimal(1))
    implicit val jsonRpcResponseMessage = JsonRpcResponseMessage(
      Right(
        Json.obj(
          "zone" -> Json.parse( s"""{"id":"158842d1-38c7-4ad3-ab83-d4c723c9aaf3","equityAccountId":0,"members":[{"id":0,"ownerPublicKey":"${ByteString.of(publicKeyBytes: _*).base64}","name":"Banker"}],"accounts":[{"id":0,"ownerMemberIds":[0],"name":"Bank"}],"transactions":[],"created":1436179968835,"expires":1436179968835,"name":"Dave's zone"}""")
        )
      ),
      Some(Right(1))
    )
    implicit val method = "createZone"
    it should behave like responseRead
    it should behave like responseWrite
  }

  private def responseReadError(jsonRpcResponseMessage: JsonRpcResponseMessage, method: String, jsError: JsError) =
    it(s"should fail to decode with error $jsError") {
      (Response.read(jsonRpcResponseMessage, method)
        should equal(jsError))(after being ordered[Either[ErrorResponse, ResultResponse]])
    }

  private def responseRead(implicit jsonRpcResponseMessage: JsonRpcResponseMessage,
                   method: String,
                   errorOrResponse: Either[ErrorResponse, ResultResponse]) =
    it(s"should decode to $errorOrResponse") {
      Response.read(jsonRpcResponseMessage, method) should be(JsSuccess(errorOrResponse))
    }

  private def responseWrite(implicit errorOrResponse: Either[ErrorResponse, ResultResponse],
                    id: Either[String, BigDecimal],
                    jsonRpcResponseMessage: JsonRpcResponseMessage) =
    it(s"should encode to $jsonRpcResponseMessage") {
      Response.write(errorOrResponse, Some(id)) should be(jsonRpcResponseMessage)
    }

  describe("A Notification") {
    describe("with an invalid method") {
      it should behave like notificationReadError(
        JsonRpcNotificationMessage(
          "invalidMethod",
          Right(Json.obj())
        ),
        None
      )
    }
    describe("of type ClientJoinedZoneNotification") {
      describe("with params of the wrong type") {
        it should behave like notificationReadError(
          JsonRpcNotificationMessage(
            "clientJoinedZone",
            Left(Json.arr())
          ),
          Some(JsError(
            List(
              (__, List(ValidationError("notification parameters must be named")))
            )
          ))
        )
      }
      describe("with empty params") {
        it should behave like notificationReadError(
          JsonRpcNotificationMessage(
            "clientJoinedZone",
            Right(Json.obj())
          ),
          Some(JsError(
            List(
              (__ \ "zoneId", List(ValidationError("error.path.missing"))),
              (__ \ "publicKey", List(ValidationError("error.path.missing")))
            )
          ))
        )
      }
      val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
      implicit val clientJoinedZoneNotification = ClientJoinedZoneNotification(
        ZoneId(UUID.fromString("a52e984e-f0aa-4481-802b-74622cb3f6f6")),
        PublicKey(publicKeyBytes)
      )
      implicit val jsonRpcNotificationMessage = JsonRpcNotificationMessage(
        "clientJoinedZone",
        Right(
          Json.obj(
            "zoneId" -> "a52e984e-f0aa-4481-802b-74622cb3f6f6",
            "publicKey" -> ByteString.of(publicKeyBytes: _*).base64
          )
        )
      )
      it should behave like notificationRead
      it should behave like notificationWrite
    }
  }

  private def notificationReadError(jsonRpcNotificationMessage: JsonRpcNotificationMessage, jsError: Option[JsError]) =
    it(s"should fail to decode with error $jsError") {
      val notificationJsResult = Notification.read(jsonRpcNotificationMessage)
      jsError.fold(notificationJsResult shouldBe empty)(
        notificationJsResult.value should equal(_)(after being ordered[Notification])
      )
    }

  private def notificationRead(implicit jsonRpcNotificationMessage: JsonRpcNotificationMessage,
                               notification: Notification) =
    it(s"should decode to $notification") {
      Notification.read(jsonRpcNotificationMessage) should be(Some(JsSuccess(notification)))
    }

  private def notificationWrite(implicit notification: Notification, jsonRpcNotificationMessage:
  JsonRpcNotificationMessage) =
    it(s"should encode to $jsonRpcNotificationMessage") {
      Notification.write(notification) should be(jsonRpcNotificationMessage)
    }

  private def ordered[A] = new JsResultUniformity[A]

}