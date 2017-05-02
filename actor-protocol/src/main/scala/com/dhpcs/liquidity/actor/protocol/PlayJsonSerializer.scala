package com.dhpcs.liquidity.actor.protocol

import java.nio.charset.StandardCharsets

import akka.serialization.SerializerWithStringManifest
import com.dhpcs.liquidity.actor.protocol.PlayJsonSerializer._
import play.api.libs.json.{Format => JFormat, _}

import scala.reflect.ClassTag

object PlayJsonSerializer {

  class Format[A <: AnyRef](format: JFormat[A]) {

    def toJson(o: AnyRef): JsValue = format.writes(o.asInstanceOf[A])

    def fromJson(json: JsValue): A = format.reads(json).recoverTotal(e => sys.error(s"Failed to read $json: $e"))

  }

  def manifestToFormat[A <: AnyRef: ClassTag: JFormat]: (String, Format[A]) =
    name(implicitly[ClassTag[A]].runtimeClass) -> new Format(implicitly[JFormat[A]])

  private def name[A](clazz: Class[A]): String = clazz.getName

}

abstract class PlayJsonSerializer extends SerializerWithStringManifest {

  override def manifest(o: AnyRef): String = name(o.getClass)

  override def toBinary(o: AnyRef): Array[Byte] =
    Json
      .stringify(
        format(name(o.getClass)).toJson(o)
      )
      .getBytes(StandardCharsets.UTF_8)

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    format(manifest).fromJson(
      Json.parse(new String(bytes, StandardCharsets.UTF_8))
    )

  protected def formats: Map[String, Format[_ <: AnyRef]]

  private def format(manifest: String): Format[_ <: AnyRef] =
    formats.getOrElse(manifest, sys.error(s"No format found for $manifest"))

}
