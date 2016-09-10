package com.dhpcs.liquidity.model

import java.nio.charset.StandardCharsets

import akka.serialization.SerializerWithStringManifest
import com.dhpcs.liquidity.model.PlayJsonEventSerializer._
import play.api.libs.json._

import scala.reflect.ClassTag

object PlayJsonEventSerializer {

  private class PlayJsonEventFormat[A](classTag: ClassTag[A], format: Format[A]) {

    def fromJson(json: JsValue): A =
      format.reads(json)
        .recoverTotal(e => sys.error(s"Failed to read $json: $e"))

    def toJson(o: Any): JsValue =
      if (classTag.runtimeClass.isInstance(o)) format.writes(o.asInstanceOf[A])
      else sys.error(s"$o is not an instance of ${classTag.runtimeClass.getName}")

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

  private def manifestToFormat[A](implicit classTag: ClassTag[A], format: Format[A]): (String, PlayJsonEventFormat[A]) =
    name(classTag.runtimeClass) -> new PlayJsonEventFormat(classTag, format)

  private def name[A](clazz: Class[A]): String = clazz.getName

}

class PlayJsonEventSerializer extends SerializerWithStringManifest {

  override def identifier: Int = 262953465

  override def manifest(obj: AnyRef): String = name(obj.getClass)

  override def toBinary(obj: AnyRef): Array[Byte] =
    Json.stringify(
      formats(name(obj.getClass)).toJson(obj)
    ).getBytes(StandardCharsets.UTF_8)

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    formats(manifest).fromJson(
      Json.parse(new String(bytes, StandardCharsets.UTF_8))
    )
}
