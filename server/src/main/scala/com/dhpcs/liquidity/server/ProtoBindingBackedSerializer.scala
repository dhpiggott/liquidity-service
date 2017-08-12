package com.dhpcs.liquidity.server

import java.io.NotSerializableException
import java.util.concurrent.atomic.AtomicReference

import akka.actor.ExtendedActorSystem
import akka.remote.serialization.ProtobufSerializer
import akka.serialization.SerializerWithStringManifest
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.server.ProtoBindingBackedSerializer.AnyRefProtoBinding

import scala.annotation.tailrec
import scala.collection.immutable.Seq
import scala.reflect.ClassTag
import scala.util.{Failure, Success}

object ProtoBindingBackedSerializer {

  object AnyRefProtoBinding {
    def apply[S, P](implicit scalaClassTag: ClassTag[S],
                    protoClassTag: ClassTag[P],
                    protoBinding: ProtoBinding[S, P, ExtendedActorSystem]): AnyRefProtoBinding[S, P] =
      new AnyRefProtoBinding(scalaClassTag, protoClassTag, protoBinding)
  }

  class AnyRefProtoBinding[S, P](val scalaClassTag: ClassTag[S],
                                 val protoClassTag: ClassTag[P],
                                 protoBinding: ProtoBinding[S, P, ExtendedActorSystem]) {
    def anyRefScalaAsAnyRefProto(s: AnyRef): AnyRef = protoBinding.asProto(s.asInstanceOf[S]).asInstanceOf[AnyRef]
    def anyRefProtoAsAnyRefScala(p: AnyRef)(implicit system: ExtendedActorSystem): AnyRef =
      protoBinding.asScala(p.asInstanceOf[P]).asInstanceOf[AnyRef]
  }
}

abstract class ProtoBindingBackedSerializer(system: ExtendedActorSystem,
                                            protoBindings: Seq[AnyRefProtoBinding[_, _]],
                                            override val identifier: Int)
    extends SerializerWithStringManifest {

  private[this] val scalaClassToProtoBinding: Map[Class[_], AnyRefProtoBinding[_, _]] = {
    val scalaClasses = protoBindings.map(_.scalaClassTag.runtimeClass)
    require(scalaClasses == scalaClasses.distinct, "Duplicate Scala classes: " + scalaClasses.mkString(", "))
    (for (protoBinding <- protoBindings) yield protoBinding.scalaClassTag.runtimeClass -> protoBinding).toMap
  }

  private[this] val protoClassToProtoBinding: Map[Class[_], AnyRefProtoBinding[_, _]] = {
    val protoClasses = protoBindings.map(_.protoClassTag.runtimeClass)
    require(protoClasses == protoClasses.distinct, "Duplicate Proto classes: " + protoClasses.mkString(", "))
    (for (protoBinding <- protoBindings) yield protoBinding.protoClassTag.runtimeClass -> protoBinding).toMap
  }

  private[this] val protobufSerializer = new ProtobufSerializer(system)
  private[this] val manifestCache      = new AtomicReference[Map[String, Class[_]]](Map.empty)

  override def manifest(o: AnyRef): String =
    scalaClassToProtoBinding
      .getOrElse(o.getClass, throw new IllegalArgumentException(s"No ProtoBinding registered for [${o.getClass}]"))
      .protoClassTag
      .runtimeClass
      .getName

  override def toBinary(o: AnyRef): Array[Byte] =
    protobufSerializer.toBinary(
      scalaClassToProtoBinding
        .getOrElse(o.getClass, throw new IllegalArgumentException(s"No ProtoBinding registered for [${o.getClass}]"))
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
    protoClassToProtoBinding
      .getOrElse(protoClass, throw new IllegalArgumentException(s"No ProtoBinding registered for [$protoClass]"))
      .anyRefProtoAsAnyRefScala(
        protobufSerializer.fromBinary(bytes, Some(protoClass))
      )(system)
  }
}
