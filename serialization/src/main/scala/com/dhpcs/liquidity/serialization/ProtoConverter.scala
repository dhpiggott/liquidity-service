package com.dhpcs.liquidity.serialization

import play.api.libs.json._
import shapeless.{:+:, ::, Coproduct, Generic, HList, Inl, Inr, Lazy}

object ProtoConverter extends LowPriorityImplicits {

  def instance[S, P](apply: S => P, unapply: P => S): ProtoConverter[S, P] = new ProtoConverter[S, P] {
    override def asProto(s: S): P = apply(s)
    override def asScala(p: P): S = unapply(p)
  }

  def apply[S, P](implicit protoConverter: ProtoConverter[S, P]): ProtoConverter[S, P] = protoConverter

  implicit def identityProtoConverter[A]: ProtoConverter[A, A] = ProtoConverter.instance(identity, identity)

  implicit final val JsValueProtoConverter: ProtoConverter[JsValue, com.google.protobuf.struct.Value] =
    new ProtoConverter[JsValue, com.google.protobuf.struct.Value] {
      override def asProto(jsValue: JsValue): com.google.protobuf.struct.Value =
        com.google.protobuf.struct.Value(jsValue match {
          case JsNull =>
            com.google.protobuf.struct.Value.Kind.NullValue(com.google.protobuf.struct.NullValue.NULL_VALUE)
          case JsBoolean(value) => com.google.protobuf.struct.Value.Kind.BoolValue(value)
          case JsNumber(value)  => com.google.protobuf.struct.Value.Kind.NumberValue(value.doubleValue())
          case JsString(value)  => com.google.protobuf.struct.Value.Kind.StringValue(value)
          case JsArray(value) =>
            com.google.protobuf.struct.Value.Kind
              .ListValue(com.google.protobuf.struct.ListValue(value.map(JsValueProtoConverter.asProto)))
          case JsObject(value) =>
            com.google.protobuf.struct.Value.Kind
              .StructValue(com.google.protobuf.struct.Struct(value.mapValues(JsValueProtoConverter.asProto).toMap))
        })
      override def asScala(value: com.google.protobuf.struct.Value): JsValue = value.kind match {
        case com.google.protobuf.struct.Value.Kind.Empty                    => throw new IllegalArgumentException("Empty Kind")
        case com.google.protobuf.struct.Value.Kind.NullValue(_)             => JsNull
        case com.google.protobuf.struct.Value.Kind.NumberValue(numberValue) => JsNumber(numberValue)
        case com.google.protobuf.struct.Value.Kind.StringValue(stringValue) => JsString(stringValue)
        case com.google.protobuf.struct.Value.Kind.BoolValue(boolValue)     => JsBoolean(boolValue)
        case com.google.protobuf.struct.Value.Kind.StructValue(structValue) =>
          JsObject(structValue.fields.mapValues(JsValueProtoConverter.asScala))
        case com.google.protobuf.struct.Value.Kind.ListValue(listValue) =>
          JsArray(listValue.values.map(JsValueProtoConverter.asScala))
      }
    }

}

trait ProtoConverter[S, P] {
  def asProto(s: S): P
  def asScala(p: P): S
}

trait LowPriorityImplicits {

  implicit def genericProtoConverter[S, P, SRepr, PRepr](
      implicit sGen: Generic.Aux[S, SRepr],
      pGen: Generic.Aux[P, PRepr],
      hlistConverter: ProtoConverter[SRepr, PRepr]
  ): ProtoConverter[S, P] = new ProtoConverter[S, P] {
    override def asProto(s: S): P = {
      val gen  = sGen.to(s)
      val repr = hlistConverter.asProto(gen)
      pGen.from(repr)
    }
    override def asScala(p: P): S = {
      val gen  = pGen.to(p)
      val repr = hlistConverter.asScala(gen)
      sGen.from(repr)
    }
  }

  implicit def coproductProtoConverter[SL, PL, SRRepr <: Coproduct, PRRepr <: Coproduct](
      implicit lConverter: Lazy[ProtoConverter[SL, PL]],
      rConverter: Lazy[ProtoConverter[SRRepr, PRRepr]]
  ): ProtoConverter[SL :+: SRRepr, PL :+: PRRepr] = new ProtoConverter[SL :+: SRRepr, PL :+: PRRepr] {
    override def asProto(s: SL :+: SRRepr): PL :+: PRRepr = s match {
      case Inl(head) => Inl(lConverter.value.asProto(head))
      case Inr(tail) => Inr(rConverter.value.asProto(tail))
    }
    override def asScala(p: PL :+: PRRepr): SL :+: SRRepr = p match {
      case Inl(head) => Inl(lConverter.value.asScala(head))
      case Inr(tail) => Inr(rConverter.value.asScala(tail))
    }
  }

  implicit def hlistProtoConverter[SH, PH, STRepr <: HList, PTRepr <: HList](
      implicit hConverter: Lazy[ProtoConverter[SH, PH]],
      tConverter: Lazy[ProtoConverter[STRepr, PTRepr]]
  ): ProtoConverter[SH :: STRepr, PH :: PTRepr] = new ProtoConverter[SH :: STRepr, PH :: PTRepr] {
    override def asProto(s: SH :: STRepr): PH :: PTRepr = {
      val head = hConverter.value.asProto(s.head)
      val tail = tConverter.value.asProto(s.tail)
      head :: tail
    }
    override def asScala(p: PH :: PTRepr): SH :: STRepr = {
      val head = hConverter.value.asScala(p.head)
      val tail = tConverter.value.asScala(p.tail)
      head :: tail
    }
  }
}
