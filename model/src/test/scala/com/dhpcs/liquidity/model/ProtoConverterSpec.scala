package com.dhpcs.liquidity.model

import org.scalatest.FreeSpec
import play.api.libs.json.{JsObject, Json}

class ProtoConverterSpec extends FreeSpec {

  case class IdWrapper(id: Long)

  case class ScalaProduct(id: IdWrapper, created: Long, name: Option[String], metadata: Option[JsObject])

  case class ProtoProduct(id: Long,
                          created: Long,
                          name: Option[String],
                          metadata: Option[com.google.protobuf.struct.Struct])

  implicit final val IdWrapperProtoConverter: ProtoConverter[IdWrapper, Long] =
    ProtoConverter.instance(_.id, IdWrapper)

  "A ScalaProduct" - {
    val product = ScalaProduct(
      id = IdWrapper(0L),
      created = 1433611420487L,
      name = Some("test"),
      metadata = Some(
        Json.obj(
          "currency" -> "GBP"
        )
      )
    )
    val productProto = ProtoProduct(
      id = 0L,
      created = 1433611420487L,
      name = Some("test"),
      metadata = Some(
        com.google.protobuf.struct.Struct(
          Map(
            "currency" -> com.google.protobuf.struct.Value(com.google.protobuf.struct.Value.Kind.StringValue("GBP"))
          )
        )
      )
    )
    s"will convert to $productProto" in assert(
      ProtoConverter[ScalaProduct, ProtoProduct].asProto(product) === productProto
    )
    s"will convert from $product" in assert(
      ProtoConverter[ScalaProduct, ProtoProduct].asScala(productProto) === product
    )
  }

  sealed trait ScalaCoproduct
  case class ScalaCoproductInstance1(id: IdWrapper, created: Long) extends ScalaCoproduct
  case class ScalaCoproductInstance2(id: IdWrapper, name: String)  extends ScalaCoproduct

  sealed trait ProtoCoproduct
  case class ProtoCoproductInstance1(id: Long, created: Long) extends ProtoCoproduct
  case class ProtoCoproductInstance2(id: Long, name: String)  extends ProtoCoproduct

  "A ScalaCoproductInstance1" - {
    val coproductInstance1: ScalaCoproduct      = ScalaCoproductInstance1(id = IdWrapper(0L), created = 1433611420487L)
    val coproductInstance1Proto: ProtoCoproduct = ProtoCoproductInstance1(id = 0L, created = 1433611420487L)
    s"will convert to $coproductInstance1Proto" in assert(
      ProtoConverter[ScalaCoproduct, ProtoCoproduct].asProto(coproductInstance1) === coproductInstance1Proto
    )
    s"will convert from $coproductInstance1" in assert(
      ProtoConverter[ScalaCoproduct, ProtoCoproduct].asScala(coproductInstance1Proto) === coproductInstance1
    )
  }

  "A ScalaCoproductInstance2" - {
    val coproductInstance2: ScalaCoproduct      = ScalaCoproductInstance2(id = IdWrapper(0L), name = "test")
    val coproductInstance2Proto: ProtoCoproduct = ProtoCoproductInstance2(id = 0L, name = "test")
    s"will convert to $coproductInstance2Proto" in assert(
      ProtoConverter[ScalaCoproduct, ProtoCoproduct].asProto(coproductInstance2) === coproductInstance2Proto
    )
    s"will convert from $coproductInstance2" in assert(
      ProtoConverter[ScalaCoproduct, ProtoCoproduct].asScala(coproductInstance2Proto) === coproductInstance2
    )
  }
}
