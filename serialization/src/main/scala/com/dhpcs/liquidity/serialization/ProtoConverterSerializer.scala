package com.dhpcs.liquidity.serialization

import java.io.NotSerializableException
import java.util.concurrent.atomic.AtomicReference

import akka.actor.ExtendedActorSystem
import akka.remote.serialization.ProtobufSerializer
import akka.serialization.SerializerWithStringManifest
import com.dhpcs.liquidity.serialization.ProtoConverterSerializer.AnyRefProtoConverter

import scala.annotation.tailrec
import scala.collection.immutable.Seq
import scala.reflect.ClassTag
import scala.util.{Failure, Success}

object ProtoConverterSerializer {

  object AnyRefProtoConverter {
    def apply[S, P](implicit scalaClassTag: ClassTag[S],
                    protoClassTag: ClassTag[P],
                    protoConverter: ProtoConverter[S, P]): AnyRefProtoConverter[S, P] =
      new AnyRefProtoConverter(scalaClassTag, protoClassTag, protoConverter)
  }

  class AnyRefProtoConverter[S, P](val scalaClassTag: ClassTag[S],
                                   val protoClassTag: ClassTag[P],
                                   protoConverter: ProtoConverter[S, P]) {
    def anyRefScalaAsAnyRefProto(s: AnyRef): AnyRef = protoConverter.asProto(s.asInstanceOf[S]).asInstanceOf[AnyRef]
    def anyRefProtoAsAnyRefScala(p: AnyRef): AnyRef = protoConverter.asScala(p.asInstanceOf[P]).asInstanceOf[AnyRef]
  }
}

abstract class ProtoConverterSerializer(system: ExtendedActorSystem,
                                        protoConverters: Seq[AnyRefProtoConverter[_, _]],
                                        override val identifier: Int)
    extends SerializerWithStringManifest {

  private[this] val scalaClassToProtoConverter: Map[Class[_], AnyRefProtoConverter[_, _]] = {
    val scalaClasses = protoConverters.map(_.scalaClassTag.runtimeClass)
    require(scalaClasses == scalaClasses.distinct, "Duplicate Scala classes: " + scalaClasses.mkString(", "))
    (for (protoConverter <- protoConverters) yield protoConverter.scalaClassTag.runtimeClass -> protoConverter).toMap
  }

  private[this] val protoClassToProtoConverter: Map[Class[_], AnyRefProtoConverter[_, _]] = {
    val protoClasses = protoConverters.map(_.protoClassTag.runtimeClass)
    require(protoClasses == protoClasses.distinct, "Duplicate Proto classes: " + protoClasses.mkString(", "))
    (for (protoConverter <- protoConverters) yield protoConverter.protoClassTag.runtimeClass -> protoConverter).toMap
  }

  private[this] val protobufSerializer = new ProtobufSerializer(system)
  private[this] val manifestCache      = new AtomicReference[Map[String, Class[_]]](Map.empty)

  override def manifest(o: AnyRef): String =
    scalaClassToProtoConverter
      .getOrElse(o.getClass, sys.error(s"No ProtoConverter registered for [${o.getClass}]"))
      .protoClassTag
      .runtimeClass
      .getName

  override def toBinary(o: AnyRef): Array[Byte] =
    protobufSerializer.toBinary(
      scalaClassToProtoConverter
        .getOrElse(o.getClass, sys.error(s"No ProtoConverter registered for [${o.getClass}]"))
        .anyRefScalaAsAnyRefProto(o)
    )

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    @tailrec def updateCache(cache: Map[String, Class[_]], key: String, value: Class[_]): Boolean =
      manifestCache.compareAndSet(cache, cache.updated(key, value)) || updateCache(manifestCache.get, key, value)
    val cache = manifestCache.get
    val protoClass = cache.get(manifest) match {
      case None =>
        system.dynamicAccess.getClassFor[AnyRef](manifest) match {
          case Failure(_) =>
            throw new NotSerializableException(s"Cannot find manifest class [$manifest].")
          case Success(loadedProtoClass) =>
            updateCache(cache, manifest, loadedProtoClass)
            loadedProtoClass
        }
      case Some(cachedProtoClass) =>
        cachedProtoClass
    }
    protoClassToProtoConverter
      .getOrElse(protoClass, sys.error(s"No ProtoConverter registered for [$protoClass]"))
      .anyRefProtoAsAnyRefScala(
        protobufSerializer.fromBinary(bytes, Some(protoClass))
      )
  }
}
