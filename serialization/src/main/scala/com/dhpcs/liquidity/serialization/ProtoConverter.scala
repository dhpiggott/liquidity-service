package com.dhpcs.liquidity.serialization

import shapeless._

import scala.reflect.ClassTag

object ProtoConverter extends LowPriorityImplicits {

  def instance[S, P](apply: S => P, unapply: P => S): ProtoConverter[S, P] = new ProtoConverter[S, P] {
    override def asProto(s: S): P = apply(s)
    override def asScala(p: P): S = unapply(p)
  }

  def apply[S, P](implicit protoConverter: ProtoConverter[S, P]): ProtoConverter[S, P] = protoConverter

  implicit def identityProtoConverter[A]: ProtoConverter[A, A] = ProtoConverter.instance(identity, identity)

}

trait ProtoConverter[S, P] {
  def asProto(s: S): P
  def asScala(p: P): S
}

sealed abstract class LowPriorityImplicits extends LowerPriorityImplicits {

  implicit def genericProtoConverter[S, P, SRepr, PRepr](
      implicit sGen: Generic.Aux[S, SRepr],
      pGen: Generic.Aux[P, PRepr],
      genericConverter: Lazy[ProtoConverter[SRepr, PRepr]]
  ): ProtoConverter[S, P] = new ProtoConverter[S, P] {
    override def asProto(s: S): P = {
      val gen  = sGen.to(s)
      val repr = genericConverter.value.asProto(gen)
      pGen.from(repr)
    }
    override def asScala(p: P): S = {
      val gen  = pGen.to(p)
      val repr = genericConverter.value.asScala(gen)
      sGen.from(repr)
    }
  }

  implicit def optionProtoConverter[S, P](
      implicit protoConverter: ProtoConverter[S, P],
      protoClassTag: ClassTag[P]
  ): ProtoConverter[S, Option[P]] = new ProtoConverter[S, Option[P]] {
    override def asProto(s: S): Option[P] =
      Some(protoConverter.asProto(s))
    override def asScala(maybeP: Option[P]): S =
      protoConverter.asScala(
        maybeP.getOrElse(throw new IllegalArgumentException(s"Empty ${protoClassTag.runtimeClass.getName}"))
      )
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
}

sealed abstract class LowerPriorityImplicits {
  implicit def wrappedOneofProtoConverter[S, PV, PW](implicit pwGen: Lazy[Generic.Aux[PW, PV :: HNil]],
                                                     vConverter: Lazy[ProtoConverter[S, PV]]): ProtoConverter[S, PW] =
    new ProtoConverter[S, PW] {
      override def asProto(s: S): PW = pwGen.value.from(vConverter.value.asProto(s) :: HNil)
      override def asScala(p: PW): S = vConverter.value.asScala(pwGen.value.to(p).head)
    }
}
