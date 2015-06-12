package com.dhpcs.json

import org.scalactic.Uniformity
import org.scalatest.{FunSpecLike, Matchers}
import play.api.libs.json._

trait FormatBehaviors[T] {
  this: FunSpecLike with Matchers =>

  def decodeError(badJson: JsValue, jsError: JsError)(implicit format: Format[T]) =
    it(s"$badJson should fail to decode with error $jsError") {
      (Json.fromJson(badJson)(format) should equal(jsError))(after being ordered)
    }

  def decode(implicit json: JsValue, t: T, format: Format[T]) =
    it(s"$json should decode to $t") {
      Json.fromJson(json)(format) should be(JsSuccess(t))
    }

  def encode(implicit t: T, json: JsValue, format: Format[T]) =
    it(s"$t should encode to $json") {
      Json.toJson(t)(format) should be(json)
    }

  def ordered = new Uniformity[JsResult[T]] {

    override def normalizedCanHandle(b: Any) = b match {
      case _: JsError => true
      case _ => false
    }

    override def normalizedOrSame(b: Any) = b match {
      case jsError: JsError => normalized(jsError)
      case _ => b
    }

    override def normalized(a: JsResult[T]) = a match {
      case jsError: JsError =>
        jsError.copy(
          errors = jsError.errors.sortBy { case (jsPath: JsPath, _) => jsPath.toJsonString }
        )
      case _ => a
    }

  }

}
