package com.dhpcs.jsonrpc

import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._

sealed trait JsonRpcMessage

object JsonRpcMessage {

  val Version = "2.0"

  implicit val JsonRpcMessageFormat: Format[JsonRpcMessage] = new Format[JsonRpcMessage] {

    override def reads(jsValue: JsValue) = (
      __.read[JsonRpcRequestMessage].map(m => m: JsonRpcMessage) orElse
        __.read[JsonRpcResponseMessage].map(m => m: JsonRpcMessage) orElse
        __.read[JsonRpcNotificationMessage].map(m => m: JsonRpcMessage)
      ).reads(jsValue).orElse(JsError("not a valid request, response or notification message"))

    override def writes(jsonRpcMessage: JsonRpcMessage) = jsonRpcMessage match {
      case jsonRpcRequestMessage: JsonRpcRequestMessage =>
        Json.toJson(jsonRpcRequestMessage)(JsonRpcRequestMessage.JsonRpcRequestMessageFormat)
      case jsonRpcResponseMessage: JsonRpcResponseMessage =>
        Json.toJson(jsonRpcResponseMessage)(JsonRpcResponseMessage.JsonRpcResponseMessageFormat)
      case jsonRpcNotificationMessage: JsonRpcNotificationMessage =>
        Json.toJson(jsonRpcNotificationMessage)(JsonRpcNotificationMessage.JsonRpcNotificationMessageFormat)
    }

  }

}

abstract class JsonRpcMessageCompanion {

  implicit val IdFormat = eitherValueFormat[String, Int]
  implicit val ParamsFormat = eitherValueFormat[JsArray, JsObject]

  def eitherObjectFormat[L: Format, R: Format](leftKey: String, rightKey: String) = OFormat[Either[L, R]](

    (__ \ leftKey).read[L].map(a => Left(a): Either[L, R]) orElse
      (__ \ rightKey).read[R].map(b => Right(b): Either[L, R]),

    OWrites[Either[L, R]] {
      case Left(leftValue) => Json.obj(leftKey -> leftValue)
      case Right(rightValue) => Json.obj(rightKey -> rightValue)
    }

  )

  def eitherValueFormat[L: Format, R: Format]: Format[Either[L, R]] = Format[Either[L, R]](

    __.read[L].map(a => Left(a): Either[L, R]) orElse
      __.read[R].map(b => Right(b): Either[L, R]),

    Writes[Either[L, R]] {
      case Left(leftValue) => Json.toJson[L](leftValue)
      case Right(rightValue) => Json.toJson[R](rightValue)
    }

  )

}

case class JsonRpcRequestMessage(method: String,
                                 params: Either[JsArray, JsObject],
                                 id: Either[String, Int]) extends JsonRpcMessage

object JsonRpcRequestMessage extends JsonRpcMessageCompanion {

  implicit val JsonRpcRequestMessageFormat: Format[JsonRpcRequestMessage] = (
    (__ \ "jsonrpc").format(verifying[String](_ == JsonRpcMessage.Version)) and
      (__ \ "method").format[String] and
      (__ \ "params").format[Either[JsArray, JsObject]] and
      (__ \ "id").format[Either[String, Int]]
    )((_, method, params, id) =>
    JsonRpcRequestMessage(method, params, id),
      jsonRpcRequestMessage =>
        (JsonRpcMessage.Version,
          jsonRpcRequestMessage.method,
          jsonRpcRequestMessage.params,
          jsonRpcRequestMessage.id)
    )

}

case class JsonRpcResponseError(code: Int,
                                message: String,
                                data: Option[JsValue])

object JsonRpcResponseError {

  implicit val JsonRpcResponseErrorFormat = Json.format[JsonRpcResponseError]

}

case class JsonRpcResponseMessage(eitherErrorOrResult: Either[JsonRpcResponseError, JsValue],
                                  id: Either[String, Int]) extends JsonRpcMessage

object JsonRpcResponseMessage extends JsonRpcMessageCompanion {

  implicit val JsonRpcResponseMessageFormat: Format[JsonRpcResponseMessage] = (
    (__ \ "jsonrpc").format(verifying[String](_ == JsonRpcMessage.Version)) and
      __.format(eitherObjectFormat[JsonRpcResponseError, JsValue]("error", "result")) and
      (__ \ "id").format[Either[String, Int]]
    )((_, eitherErrorOrResult, id) =>
    JsonRpcResponseMessage(eitherErrorOrResult, id),
      jsonRpcResponseMessage =>
        (JsonRpcMessage.Version,
          jsonRpcResponseMessage.eitherErrorOrResult,
          jsonRpcResponseMessage.id)
    )

}

case class JsonRpcNotificationMessage(method: String,
                                      params: Either[JsArray, JsObject]) extends JsonRpcMessage

object JsonRpcNotificationMessage extends JsonRpcMessageCompanion {

  implicit val JsonRpcNotificationMessageFormat: Format[JsonRpcNotificationMessage] = (
    (__ \ "jsonrpc").format(verifying[String](_ == JsonRpcMessage.Version)) and
      (__ \ "method").format[String] and
      (__ \ "params").format[Either[JsArray, JsObject]]
    )((_, method, params) =>
    JsonRpcNotificationMessage(method, params),
      jsonRpcNotificationMessage =>
        (JsonRpcMessage.Version,
          jsonRpcNotificationMessage.method,
          jsonRpcNotificationMessage.params)
    )

} 