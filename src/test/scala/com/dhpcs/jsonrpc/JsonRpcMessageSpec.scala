package com.dhpcs.jsonrpc

import org.scalatest._
import play.api.data.validation.ValidationError
import play.api.libs.json._

// TODO: Matchers needed?
class JsonRpcMessageSpec extends FunSpec with Matchers {

  val TestArray = JsArray(
    Seq(
      JsString("param1"),
      JsString("param2")
    )
  )

  val TestError = JsonRpcResponseError(
    0,
    "testError",
    None
  )

  val TestIdentifierInt = 0

  val TestIdentifierString = "zero"

  val TestMethod = "testMethod"

  val TestObject = JsObject(
    Seq(
      "param1" -> JsString("param1"),
      "param2" -> JsString("param2")
    )
  )

  def decode[T <: JsonRpcMessage : Format](implicit jsonRpcMessageJson: JsValue, jsonRpcMessage: T) =
    it(s"$jsonRpcMessageJson should decode to $jsonRpcMessage") {
      jsonRpcMessageJson.as[T] should be(jsonRpcMessage)
    }

  // TODO: Decode errors
  def decodeError[T <: JsonRpcMessage : Format](badJsonRpcMessageJson: JsValue, jsError: JsError) =
    it(s"$badJsonRpcMessageJson should not decode") {
      Json.fromJson[T](badJsonRpcMessageJson) should be(jsError)
    }

  def encode[T <: JsonRpcMessage : Format](implicit jsonRpcMessage: T, jsonRpcMessageJson: JsValue) =
    it(s"$jsonRpcMessage should encode to $jsonRpcMessageJson") {
      Json.toJson(jsonRpcMessage) should be(jsonRpcMessageJson)
    }

  describe("A JsonRpcRequestMessage") {
    describe("with an object params") {
      describe("with a string identifier") {
        implicit val jsonRpcRequestMessage = JsonRpcRequestMessage(
          TestMethod,
          Right(TestObject),
          Left(TestIdentifierString)
        )
        implicit val jsonRpcRequestMessageJson = Json.parse("{\"jsonrpc\":\"2.0\",\"method\":\"testMethod\",\"params\":{\"param1\":\"param1\",\"param2\":\"param2\"},\"id\":\"zero\"}")
        it should behave like encode
        it should behave like decode
        it should behave like decodeError(
          Json.parse("{\"jsonrpc\":\"2.0\",\"methods\":\"testMethod\",\"params\":{\"param1\":\"param1\",\"param2\":\"param2\"},\"id\":\"zero\"}"),
          JsError(List((__ \ "method",List(ValidationError("error.path.missing")))))
        )
      }
      describe("with an int identifier") {
        implicit val jsonRpcRequestMessage = JsonRpcRequestMessage(
          TestMethod,
          Right(TestObject),
          Right(TestIdentifierInt)
        )
        implicit val jsonRpcRequestMessageJson = Json.parse("{\"jsonrpc\":\"2.0\",\"method\":\"testMethod\",\"params\":{\"param1\":\"param1\",\"param2\":\"param2\"},\"id\":0}")
        it should behave like encode
        it should behave like decode
      }
    }
    describe("with an array params") {
      describe("with a string identifier") {
        implicit val jsonRpcRequestMessage = JsonRpcRequestMessage(
          TestMethod,
          Left(TestArray),
          Left(TestIdentifierString)
        )
        implicit val jsonRpcRequestMessageJson = Json.parse("{\"jsonrpc\":\"2.0\",\"method\":\"testMethod\",\"params\":[\"param1\",\"param2\"],\"id\":\"zero\"}")
        it should behave like encode
        it should behave like decode
      }
      describe("with an int identifier") {
        implicit val jsonRpcRequestMessage = JsonRpcRequestMessage(
          TestMethod,
          Left(TestArray),
          Right(TestIdentifierInt)
        )
        implicit val jsonRpcRequestMessageJson = Json.parse("{\"jsonrpc\":\"2.0\",\"method\":\"testMethod\",\"params\":[\"param1\",\"param2\"],\"id\":0}")
        it should behave like encode
        it should behave like decode
      }
    }
  }

  describe("A JsonRpcResponseMessage") {
    describe("with a result") {
      describe("with a string identifier") {
        implicit val jsonRpcResponseMessage = JsonRpcResponseMessage(
          Right(TestObject),
          Left(TestIdentifierString)
        )
        implicit val jsonRpcResponseMessageJson = Json.parse("{\"jsonrpc\":\"2.0\",\"result\":{\"param1\":\"param1\",\"param2\":\"param2\"},\"id\":\"zero\"}")
        it should behave like encode
        it should behave like decode
      }
      describe("with an int identifier") {
        implicit val jsonRpcResponseMessage = JsonRpcResponseMessage(
          Right(TestObject),
          Right(TestIdentifierInt)
        )
        implicit val jsonRpcResponseMessageJson = Json.parse("{\"jsonrpc\":\"2.0\",\"result\":{\"param1\":\"param1\",\"param2\":\"param2\"},\"id\":0}")
        it should behave like encode
        it should behave like decode
      }
    }
    describe("with an error") {
      describe("with a string identifier") {
        implicit val jsonRpcResponseMessage = JsonRpcResponseMessage(
          Left(TestError),
          Left(TestIdentifierString)
        )
        implicit val jsonRpcResponseMessageJson = Json.parse("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":0,\"message\":\"testError\"},\"id\":\"zero\"}")
        it should behave like encode
        it should behave like decode
      }
      describe("with an int identifier") {
        implicit val jsonRpcResponseMessage = JsonRpcResponseMessage(
          Left(TestError),
          Right(TestIdentifierInt)
        )
        implicit val jsonRpcResponseMessageJson = Json.parse("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":0,\"message\":\"testError\"},\"id\":0}")
        it should behave like encode
        it should behave like decode
      }
    }
  }

  describe("A JsonRpcNotificationMessage") {
    describe("with an object params") {
      implicit val jsonRpcNotificationMessage = JsonRpcNotificationMessage(
        TestMethod,
        Right(TestObject)
      )
      implicit val jsonRpcNotificationMessageJson = Json.parse("{\"jsonrpc\":\"2.0\",\"method\":\"testMethod\",\"params\":{\"param1\":\"param1\",\"param2\":\"param2\"}}")
      it should behave like encode
      it should behave like decode
    }
    describe("with an array params") {
      implicit val jsonRpcNotificationMessage = JsonRpcNotificationMessage(
        TestMethod,
        Left(TestArray)
      )
      implicit val jsonRpcNotificationMessageJson = Json.parse("{\"jsonrpc\":\"2.0\",\"method\":\"testMethod\",\"params\":[\"param1\",\"param2\"]}")
      it should behave like encode
      it should behave like decode
    }
  }

}