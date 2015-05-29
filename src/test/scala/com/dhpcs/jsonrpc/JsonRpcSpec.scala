package com.dhpcs.jsonrpc

import com.dhpcs.liquidity.models._
import org.scalatest._
import play.api.libs.json.Json

class JsonRpcSpec extends FlatSpec with Matchers {

  "JsonRpcRequestMessage formatting with string identifiers" should "be reversible" in {
    val jsonRpcRequestMessage = Command.writeCommand(
      CreateZone("Dave's zone", "test"),
      Left("zero")
    )
    note(s"jsonRpcRequestMessage: $jsonRpcRequestMessage")
    val jsonRpcRequestMessageJson = Json.toJson(jsonRpcRequestMessage: JsonRpcMessage)
    note(s"jsonRpcRequestMessageJson: $jsonRpcRequestMessageJson")
    jsonRpcRequestMessageJson.as[JsonRpcRequestMessage] should be(jsonRpcRequestMessage)
  }

  "JsonRpcRequestMessage formatting with integer identifiers" should "be reversible" in {
    val jsonRpcRequestMessage = Command.writeCommand(
      CreateZone("Dave's zone", "test"),
      Right(0)
    )
    note(s"jsonRpcRequestMessage: $jsonRpcRequestMessage")
    val jsonRpcRequestMessageJson = Json.toJson(jsonRpcRequestMessage: JsonRpcMessage)
    note(s"jsonRpcRequestMessageJson: $jsonRpcRequestMessageJson")
    jsonRpcRequestMessageJson.as[JsonRpcRequestMessage] should be(jsonRpcRequestMessage)
  }

  "JsonRpcResponseMessage formatting with string identifiers" should "be reversible" in {
    val jsonRpcResponseMessage = CommandResponse.writeCommandResponse(
      ZoneCreated(ZoneId.generate),
      Left("zero")
    )
    note(s"jsonRpcResponseMessage: $jsonRpcResponseMessage")
    val jsonRpcResponseMessageJson = Json.toJson(jsonRpcResponseMessage: JsonRpcMessage)
    note(s"jsonRpcResponseMessageJson: $jsonRpcResponseMessageJson")
    jsonRpcResponseMessageJson.as[JsonRpcResponseMessage] should be(jsonRpcResponseMessage)
  }

  "JsonRpcResponseMessage formatting with integer identifiers" should "be reversible" in {
    val jsonRpcResponseMessage = CommandResponse.writeCommandResponse(
      ZoneCreated(ZoneId.generate),
      Right(0)
    )
    note(s"jsonRpcResponseMessage: $jsonRpcResponseMessage")
    val jsonRpcResponseMessageJson = Json.toJson(jsonRpcResponseMessage: JsonRpcMessage)
    note(s"jsonRpcResponseMessageJson: $jsonRpcResponseMessageJson")
    jsonRpcResponseMessageJson.as[JsonRpcResponseMessage] should be(jsonRpcResponseMessage)
  }

  "JsonRpcResponseMessage error formatting with string identifiers" should "be reversible" in {
    val jsonRpcResponseMessage = CommandResponse.writeCommandResponse(
      CommandErrorResponse(0, "test,", None),
      Left("zero")
    )
    note(s"jsonRpcResponseMessage: $jsonRpcResponseMessage")
    val jsonRpcResponseMessageJson = Json.toJson(jsonRpcResponseMessage: JsonRpcMessage)
    note(s"jsonRpcResponseMessageJson: $jsonRpcResponseMessageJson")
    jsonRpcResponseMessageJson.as[JsonRpcResponseMessage] should be(jsonRpcResponseMessage)
  }

  "JsonRpcResponseMessage error formatting with integer identifiers" should "be reversible" in {
    val jsonRpcResponseMessage = CommandResponse.writeCommandResponse(
      CommandErrorResponse(0, "test,", None),
      Right(0)
    )
    note(s"jsonRpcResponseMessage: $jsonRpcResponseMessage")
    val jsonRpcResponseMessageJson = Json.toJson(jsonRpcResponseMessage: JsonRpcMessage)
    note(s"jsonRpcResponseMessageJson: $jsonRpcResponseMessageJson")
    jsonRpcResponseMessageJson.as[JsonRpcResponseMessage] should be(jsonRpcResponseMessage)
  }

  "JsonRpcNotificationMessage formatting" should "be reversible" in {
    val jsonRpcNotificationMessage = Notification.writeNotification(
      MemberJoinedZone(ZoneId.generate, MemberId.generate)
    )
    note(s"jsonRpcNotificationMessage: $jsonRpcNotificationMessage")
    val jsonRpcNotificationMessageJson = Json.toJson(jsonRpcNotificationMessage: JsonRpcMessage)
    note(s"jsonRpcNotificationMessageJson: $jsonRpcNotificationMessageJson")
    jsonRpcNotificationMessageJson.as[JsonRpcNotificationMessage] should be(jsonRpcNotificationMessage)
  }

}