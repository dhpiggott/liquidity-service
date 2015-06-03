package com.dhpcs.jsonrpc

import com.dhpcs.json.ValueFormat
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._

/**
 * These types define an implementation of JSON-RPC: http://www.jsonrpc.org/specification.
 * TODO: JsonRpcMessage name should match file name, can the implicit formats be grouped in one object?
 */
sealed trait JsonRpcMessage

object JsonRpcMessage {

  val Version = "2.0"

  implicit val JsonRpcMessageFormat: Format[JsonRpcMessage] = new Format[JsonRpcMessage] {

    // TODO
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

  implicit val IdFormat = eitherValueFormat[String, Int]
  implicit val ParamsFormat = eitherValueFormat[JsArray, JsObject]

  def eitherObjectFormat[L: Format, R: Format](leftKey: String, rightKey: String) = OFormat[Either[L, R]](
    (__ \ leftKey).read[L].map(a => Left(a): Either[L, R]) orElse
      (__ \ rightKey).read[R].map(b => Right(b): Either[L, R]),
    OWrites[Either[L, R]](
      _.fold(leftValue => Json.obj(leftKey -> leftValue), rightValue => Json.obj(rightKey -> rightValue))
    )
  )

  def eitherValueFormat[L: Format, R: Format]: Format[Either[L, R]] = ValueFormat[Either[L, R], JsValue](
    jsValue => jsValue.asOpt[L].fold[Either[L, R]](Right(jsValue.as[R]))(a => Left(a)),
    _.fold[JsValue](Json.toJson[L], Json.toJson[R])
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

// TODO: Swap? See http://www.scala-lang.org/api/2.11.6/index.html#scala.util.Either
case class JsonRpcResponseMessage(eitherResultOrError: Either[JsValue, JsonRpcResponseError],
                                  id: Either[String, Int]) extends JsonRpcMessage

object JsonRpcResponseMessage extends JsonRpcMessageCompanion {

  implicit val JsonRpcResponseMessageFormat: Format[JsonRpcResponseMessage] = (
    (__ \ "jsonrpc").format(verifying[String](_ == JsonRpcMessage.Version)) and
      __.format(eitherObjectFormat[JsValue, JsonRpcResponseError]("result", "error")) and
      (__ \ "id").format[Either[String, Int]]
    )((_, eitherResultOrError, id) =>
    JsonRpcResponseMessage(eitherResultOrError, id),
      jsonRpcResponseMessage =>
        (JsonRpcMessage.Version,
          jsonRpcResponseMessage.eitherResultOrError,
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