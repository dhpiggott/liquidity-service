package com.dhpcs.liquidity.models

import java.security.KeyPairGenerator
import java.util.UUID

import com.dhpcs.json.JsResultUniformity
import com.dhpcs.jsonrpc.{JsonRpcNotificationMessage, JsonRpcRequestMessage, JsonRpcResponseMessage}
import com.google.common.io.BaseEncoding
import org.scalatest.OptionValues._
import org.scalatest._
import play.api.data.validation.ValidationError
import play.api.libs.json._

class MessageSpec extends FunSpec with Matchers {

  def commandReadError(jsonRpcRequestMessage: JsonRpcRequestMessage, maybeJsError: Option[JsError]) =
    it(s"should fail to decode with error $maybeJsError") {
      val maybeCommandJsResult = Command.readCommand(jsonRpcRequestMessage)
      maybeJsError.fold(
        maybeCommandJsResult shouldBe empty
      )(
          jsError => {
            maybeCommandJsResult.value should equal(jsError)(after being ordered[Command])
          }
        )
    }

  def commandRead(implicit jsonRpcRequestMessage: JsonRpcRequestMessage, command: Command) =
    it(s"should decode to $command") {
      Command.readCommand(jsonRpcRequestMessage) should be(Some(JsSuccess(command)))
    }

  def commandWrite(implicit command: Command, id: Either[String, Int], jsonRpcRequestMessage: JsonRpcRequestMessage) =
    it(s"should encode to $jsonRpcRequestMessage") {
      Command.writeCommand(command, id) should be(jsonRpcRequestMessage)
    }

  def ordered[A] = new JsResultUniformity[A]

  describe("A Command") {
    describe("with an invalid method") {
      it should behave like commandReadError(
        JsonRpcRequestMessage(
          "invalidMethod",
          Right(Json.obj()),
          Right(0)
        ),
        None
      )
    }
    describe("of type CreateZone") {
      describe("with params of the wrong type") {
        it should behave like commandReadError(
          JsonRpcRequestMessage(
            "createZone",
            Left(Json.arr()),
            Right(0)
          ),
          Some(
            JsError(List(
              (__, List(ValidationError("command parameters must be named")))
            ))
          )
        )
      }
      describe("with empty params") {
        it should behave like commandReadError(
          JsonRpcRequestMessage(
            "createZone",
            Right(Json.obj()),
            Right(0)
          ),
          Some(
            JsError(List(
              (__ \ "name", List(ValidationError("error.path.missing"))),
              (__ \ "zoneType", List(ValidationError("error.path.missing")))
            ))
          )
        )
      }
      implicit val createZone = CreateZone(
        "Dave's zone",
        "test"
      )
      implicit val id = Right(0)
      implicit val jsonRpcRequestMessage = JsonRpcRequestMessage(
        "createZone",
        Right(
          Json.obj(
            "name" -> "Dave's zone",
            "zoneType" -> "test"
          )
        ),
        Right(0)
      )
      it should behave like commandRead
      it should behave like commandWrite
    }
  }

  def commandResponseReadError(jsonRpcResponseMessage: JsonRpcResponseMessage,
                               method: String,
                               jsError: JsError) =
    it(s"should fail to decode with error $jsError") {
      (CommandResponse.readCommandResponse(jsonRpcResponseMessage, method)
        should equal(jsError))(after being ordered[CommandResponse])
    }

  def commandResponseRead(implicit jsonRpcResponseMessage: JsonRpcResponseMessage,
                          method: String,
                          commandResponse: CommandResponse) =
    it(s"should decode to $commandResponse") {
      CommandResponse.readCommandResponse(jsonRpcResponseMessage, method) should be(JsSuccess(commandResponse))
    }

  def commandResponseWrite(implicit commandResponse: CommandResponse,
                           id: Either[String, Int],
                           jsonRpcResponseMessage: JsonRpcResponseMessage) =
    it(s"should encode to $jsonRpcResponseMessage") {
      CommandResponse.writeCommandResponse(commandResponse, id, Json.obj()) should be(jsonRpcResponseMessage)
    }

  describe("A CommandResponse") {
    describe("of type ZoneCreated") {
      describe("with empty params") {
        it should behave like commandResponseReadError(
          JsonRpcResponseMessage(
            Right(Json.obj()),
            Some(Right(0))
          ),
          "createZone",
          JsError(List(
            (__ \ "zoneId", List(ValidationError("error.path.missing")))
          ))
        )
      }
    }
    implicit val zoneCreated = ZoneCreated(
      ZoneId(UUID.fromString("158842d1-38c7-4ad3-ab83-d4c723c9aaf3"))
    )
    implicit val id = Right(0)
    implicit val jsonRpcResponseMessage = JsonRpcResponseMessage(
      Right(
        Json.obj(
          "zoneId" -> "158842d1-38c7-4ad3-ab83-d4c723c9aaf3"
        )
      ),
      Some(
        Right(0)
      )
    )
    implicit val method = "createZone"
    it should behave like commandResponseRead
    it should behave like commandResponseWrite
  }

  def notificationReadError(jsonRpcNotificationMessage: JsonRpcNotificationMessage, maybeJsError: Option[JsError]) =
    it(s"should fail to decode with error $maybeJsError") {
      val maybeNotificationJsResult = Notification.readNotification(jsonRpcNotificationMessage)
      maybeJsError.fold(
        maybeNotificationJsResult shouldBe empty
      )(
          jsError => {
            maybeNotificationJsResult.value should equal(jsError)(after being ordered[Notification])
          }
        )
    }

  def notificationRead(implicit jsonRpcNotificationMessage: JsonRpcNotificationMessage, notification: Notification) =
    it(s"should decode to $notification") {
      Notification.readNotification(jsonRpcNotificationMessage) should be(Some(JsSuccess(notification)))
    }

  def notificationWrite(implicit notification: Notification, jsonRpcNotificationMessage: JsonRpcNotificationMessage) =
    it(s"should encode to $jsonRpcNotificationMessage") {
      Notification.writeNotification(notification) should be(jsonRpcNotificationMessage)
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
    describe("of type Notification") {
      describe("with params of the wrong type") {
        it should behave like notificationReadError(
          JsonRpcNotificationMessage(
            "zoneState",
            Left(Json.arr())
          ),
          Some(
            JsError(List(
              (__, List(ValidationError("notification parameters must be named")))
            ))
          )
        )
      }
      describe("with empty params") {
        it should behave like notificationReadError(
          JsonRpcNotificationMessage(
            "zoneState",
            Right(Json.obj())
          ),
          Some(
            JsError(List(
              (__ \ "zoneId", List(ValidationError("error.path.missing"))),
              (__ \ "zone", List(ValidationError("error.path.missing")))
            ))
          )
        )
      }
      val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
      implicit val zoneState = ZoneState(
        ZoneId(UUID.fromString("a52e984e-f0aa-4481-802b-74622cb3f6f6")),
        Zone(
          "Dave's zone",
          "test",
          Map(
            MemberId(UUID.fromString("fa781d33-368f-42a5-9c64-0e4b43381c37")) ->
              Member("Dave", PublicKey(publicKeyBytes))
          ),
          Map(
            AccountId(UUID.fromString("f2f4613c-0645-4dec-895b-2812382f4523")) ->
              Account("Dave's account", Set(MemberId(UUID.fromString("fa781d33-368f-42a5-9c64-0e4b43381c37"))))
          ),
          Map(
            TransactionId(UUID.fromString("65b1711c-5747-452c-8975-3f0d36e9efa6")) ->
              Transaction(
                "Dave's lottery win",
                AccountId(UUID.fromString("80ccbec2-79a4-4cfa-8e97-f33fac2aa5ba")),
                AccountId(UUID.fromString("f2f4613c-0645-4dec-895b-2812382f4523")),
                BigDecimal(1000000),
                1433611420487L
              )
          ),
          1433611420487L
        )
      )
      implicit val jsonRpcNotificationMessage = JsonRpcNotificationMessage(
        "zoneState",
        Right(
          Json.obj(
            "zoneId" -> "a52e984e-f0aa-4481-802b-74622cb3f6f6",
            "zone" -> Json.parse( s"""{"name":"Dave's zone","type":"test","members":{"fa781d33-368f-42a5-9c64-0e4b43381c37":{"name":"Dave","publicKey":"${BaseEncoding.base64.encode(publicKeyBytes)}"}},"accounts":{"f2f4613c-0645-4dec-895b-2812382f4523":{"name":"Dave's account","owners":["fa781d33-368f-42a5-9c64-0e4b43381c37"]}},"transactions":{"65b1711c-5747-452c-8975-3f0d36e9efa6":{"description":"Dave's lottery win","from":"80ccbec2-79a4-4cfa-8e97-f33fac2aa5ba","to":"f2f4613c-0645-4dec-895b-2812382f4523","amount":1000000,"created":1433611420487}},"lastModified":1433611420487}""")
          )
        )
      )
      it should behave like notificationRead
      it should behave like notificationWrite
    }
  }

}