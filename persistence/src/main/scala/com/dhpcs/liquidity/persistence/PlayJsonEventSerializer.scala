package com.dhpcs.liquidity.persistence

import java.nio.charset.StandardCharsets

import akka.serialization.SerializerWithStringManifest
import com.dhpcs.liquidity.persistence.PlayJsonEventSerializer._
import play.api.libs.json._

import scala.reflect.ClassTag

object PlayJsonEventSerializer {

  private class EventFormat[A](format: Format[A]) {

    def fromJson(json: JsValue): A =
      format.reads(json).recoverTotal(e => sys.error(s"Failed to read $json: $e"))

    def toJson(o: Any): JsValue =
      format.writes(o.asInstanceOf[A])
  }

  private val formats = Map(
    manifestToFormat[ZoneCreatedEvent],
    manifestToFormat[ZoneJoinedEvent],
    manifestToFormat[ZoneQuitEvent],
    manifestToFormat[ZoneNameChangedEvent],
    manifestToFormat[MemberCreatedEvent],
    manifestToFormat[MemberUpdatedEvent],
    manifestToFormat[AccountCreatedEvent],
    manifestToFormat[AccountUpdatedEvent],
    manifestToFormat[TransactionAddedEvent]
  ).withDefault(manifest => sys.error(s"No format found for $manifest"))

  private def manifestToFormat[A : ClassTag : Format]: (String, EventFormat[A]) =
    name(implicitly[ClassTag[A]].runtimeClass) -> new EventFormat(implicitly[Format[A]])

  private def name[A](clazz: Class[A]): String = clazz.getName

}

class PlayJsonEventSerializer extends SerializerWithStringManifest {

  override def identifier: Int = 262953465

  override def manifest(obj: AnyRef): String = name(obj.getClass)

  override def toBinary(o: AnyRef): Array[Byte] =
    Json.stringify(
      formats(name(o.getClass)).toJson(o)
    ).getBytes(StandardCharsets.UTF_8)

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    formats(manifest).fromJson(
      Json.parse(new String(bytes, StandardCharsets.UTF_8))
    )
}
