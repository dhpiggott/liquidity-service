package com.dhpcs.jsonrpc

import com.dhpcs.json.ValueFormat
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._

/**
 * These types define an implementation of JSON-RPC: http://www.jsonrpc.org/specification.
 */
sealed trait JsonRpcMessage

object JsonRpcMessage {

  val Version = "2.0"

  implicit val JsonRpcMessageFormat: Format[JsonRpcMessage] = new Format[JsonRpcMessage] {

    override def reads(jsValue: JsValue) = (
      __.read[JsonRpcRequestMessage].map(m => m: JsonRpcMessage) orElse
        __.read[JsonRpcResponseMessage].map(m => m: JsonRpcMessage) orElse
        __.read[JsonRpcNotificationMessage].map(m => m: JsonRpcMessage)
      ).reads(jsValue)

    override def writes(jsonRpcMessage: JsonRpcMessage) = jsonRpcMessage match {
      case jsonRpcRequestMessage: JsonRpcRequestMessage => Json.toJson(jsonRpcRequestMessage)(JsonRpcRequestMessage.JsonRpcRequestMessageFormat)
      case jsonRpcResponseMessage: JsonRpcResponseMessage => Json.toJson(jsonRpcResponseMessage)(JsonRpcResponseMessage.JsonRpcResponseMessageFormat)
      case jsonRpcNotificationMessage: JsonRpcNotificationMessage => Json.toJson(jsonRpcNotificationMessage)(JsonRpcNotificationMessage.JsonRpcNotificationMessageFormat)
    }

  }

}

abstract class JsonRpcMessageCompanion {

  implicit val IdFormat = eitherFormat[String, Int]
  implicit val ParamsFormat = eitherFormat[JsArray, JsObject]

  def eitherFormat[A: Format, B: Format]: Format[Either[A, B]] = ValueFormat.valueFormat(
    (jsValue: JsValue) => jsValue.asOpt[A].fold[Either[A, B]](Right(jsValue.as[B]))(a => Left(a))
  )((eitherAorB: Either[A, B]) => eitherAorB.fold(Json.toJson[A], Json.toJson[B]))

}

case class JsonRpcRequestMessage(method: String,
                                 params: Either[JsArray, JsObject],
                                 id: Either[String, Int]) extends JsonRpcMessage

object JsonRpcRequestMessage extends JsonRpcMessageCompanion {

  implicit val JsonRpcRequestMessageFormat: Format[JsonRpcRequestMessage] = new Format[JsonRpcRequestMessage] {

    override def reads(jsValue: JsValue) = (
      (__ \ "jsonrpc").read(verifying[String](_ == JsonRpcMessage.Version)) andKeep
        (__ \ "method").read[String] and
        (__ \ "params").read[Either[JsArray, JsObject]] and
        (__ \ "id").read[Either[String, Int]]
      )((method, params, id) =>
      JsonRpcRequestMessage(method, params, id)
      ).reads(jsValue)

    override def writes(jsonRpcRequestMessage: JsonRpcRequestMessage) = (
      (__ \ "jsonrpc").write[String] and
        (__ \ "method").write[String] and
        (__ \ "params").write[Either[JsArray, JsObject]] and
        (__ \ "id").write[Either[String, Int]]
      )((jsonRpcRequestMessage: JsonRpcRequestMessage) =>
      (JsonRpcMessage.Version,
        jsonRpcRequestMessage.method,
        jsonRpcRequestMessage.params,
        jsonRpcRequestMessage.id)
      ).writes(jsonRpcRequestMessage)

  }

}

case class JsonRpcResponseError(code: Int,
                                message: String,
                                data: Option[JsValue])

object JsonRpcResponseError {

  implicit val JsonRpcResponseErrorFormat: Format[JsonRpcResponseError] = new Format[JsonRpcResponseError] {

    override def reads(jsValue: JsValue) = (
      (__ \ "code").read[Int] and
        (__ \ "message").read[String] and
        (__ \ "data").readNullable[JsValue]
      )((code, message, data) =>
      JsonRpcResponseError(code, message, data)
      ).reads(jsValue)

    override def writes(jsonRpcResponseError: JsonRpcResponseError) = (
      (__ \ "code").write[Int] and
        (__ \ "message").write[String] and
        (__ \ "data").writeNullable[JsValue]
      )((jsonRpcResponseError: JsonRpcResponseError) =>
      (jsonRpcResponseError.code,
        jsonRpcResponseError.message,
        jsonRpcResponseError.data)
      ).writes(jsonRpcResponseError)

  }

}

case class JsonRpcResponseMessage(eitherResultOrError: Either[JsValue, JsonRpcResponseError],
                                  id: Either[String, Int]) extends JsonRpcMessage

object JsonRpcResponseMessage extends JsonRpcMessageCompanion {

  implicit val JsonRpcResponseMessageFormat: Format[JsonRpcResponseMessage] = new Format[JsonRpcResponseMessage] {

    override def reads(jsValue: JsValue) = (
      (__ \ "jsonrpc").read(verifying[String](_ == JsonRpcMessage.Version)) andKeep
        (__ \ "result").readNullable[JsValue] and
        (__ \ "error").readNullable[JsonRpcResponseError] and
        (__ \ "id").read[Either[String, Int]]
      )((result, error, id) =>
      JsonRpcResponseMessage(error.fold[Either[JsValue, JsonRpcResponseError]](Left(result.get))(Right(_)), id)
      ).reads(jsValue)

    override def writes(jsonRpcResponseMessage: JsonRpcResponseMessage) = (
      (__ \ "jsonrpc").write[String] and
        (__ \ "result").writeNullable[JsValue] and
        (__ \ "error").writeNullable[JsonRpcResponseError] and
        (__ \ "id").write[Either[String, Int]]
      )((jsonRpcResponseMessage: JsonRpcResponseMessage) =>
      (JsonRpcMessage.Version,
        jsonRpcResponseMessage.eitherResultOrError.left.toOption,
        jsonRpcResponseMessage.eitherResultOrError.right.toOption,
        jsonRpcResponseMessage.id)
      ).writes(jsonRpcResponseMessage)

  }

}

case class JsonRpcNotificationMessage(method: String,
                                      params: Either[JsArray, JsObject]) extends JsonRpcMessage

object JsonRpcNotificationMessage extends JsonRpcMessageCompanion {

  implicit val JsonRpcNotificationMessageFormat: Format[JsonRpcNotificationMessage] = new Format[JsonRpcNotificationMessage] {

    override def reads(jsValue: JsValue) = (
      (__ \ "jsonrpc").read(verifying[String](_ == JsonRpcMessage.Version)) andKeep
        (__ \ "method").read[String] and
        (__ \ "params").read[Either[JsArray, JsObject]]
      )((method, params) =>
      JsonRpcNotificationMessage(method, params)
      ).reads(jsValue)

    override def writes(jsonRpcNotificationMessage: JsonRpcNotificationMessage) = (
      (__ \ "jsonrpc").write[String] and
        (__ \ "method").write[String] and
        (__ \ "params").write[Either[JsArray, JsObject]]
      )((jsonRpcNotificationMessage: JsonRpcNotificationMessage) =>
      (JsonRpcMessage.Version,
        jsonRpcNotificationMessage.method,
        jsonRpcNotificationMessage.params)
      ).writes(jsonRpcNotificationMessage)

  }

}