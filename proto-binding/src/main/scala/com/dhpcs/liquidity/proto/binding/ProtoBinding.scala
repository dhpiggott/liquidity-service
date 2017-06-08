package com.dhpcs.liquidity.proto.binding

import shapeless._

import scala.reflect.ClassTag

object ProtoBinding extends LowPriorityImplicits {

  def instance[S, P](apply: S => P, unapply: P => S): ProtoBinding[S, P] = new ProtoBinding[S, P] {
    override def asProto(s: S): P = apply(s)
    override def asScala(p: P): S = unapply(p)
  }

  def apply[S, P](implicit protoBinding: ProtoBinding[S, P]): ProtoBinding[S, P] = protoBinding

  implicit def identityProtoBinding[A]: ProtoBinding[A, A] = ProtoBinding.instance(identity, identity)

}

trait ProtoBinding[S, P] {
  def asProto(s: S): P
  def asScala(p: P): S
}

sealed abstract class LowPriorityImplicits extends LowerPriorityImplicits {

  implicit def genericProtoBinding[S, P, SRepr, PRepr](
      implicit sGen: Generic.Aux[S, SRepr],
      pGen: Generic.Aux[P, PRepr],
      genericBinding: Lazy[ProtoBinding[SRepr, PRepr]]
  ): ProtoBinding[S, P] = new ProtoBinding[S, P] {
    override def asProto(s: S): P = {
      val gen  = sGen.to(s)
      val repr = genericBinding.value.asProto(gen)
      pGen.from(repr)
    }
    override def asScala(p: P): S = {
      val gen  = pGen.to(p)
      val repr = genericBinding.value.asScala(gen)
      sGen.from(repr)
    }
  }

  implicit def optionProtoBinding[S, P](
      implicit protoBinding: ProtoBinding[S, P],
      protoClassTag: ClassTag[P]
  ): ProtoBinding[S, Option[P]] = new ProtoBinding[S, Option[P]] {
    override def asProto(s: S): Option[P] =
      Some(protoBinding.asProto(s))
    override def asScala(maybeP: Option[P]): S =
      protoBinding.asScala(
        maybeP.getOrElse(throw new IllegalArgumentException(s"Empty ${protoClassTag.runtimeClass.getName}"))
      )
  }

  implicit def hlistProtoBinding[SH, PH, STRepr <: HList, PTRepr <: HList](
      implicit hBinding: Lazy[ProtoBinding[SH, PH]],
      tBinding: Lazy[ProtoBinding[STRepr, PTRepr]]
  ): ProtoBinding[SH :: STRepr, PH :: PTRepr] = new ProtoBinding[SH :: STRepr, PH :: PTRepr] {
    override def asProto(s: SH :: STRepr): PH :: PTRepr = {
      val head = hBinding.value.asProto(s.head)
      val tail = tBinding.value.asProto(s.tail)
      head :: tail
    }
    override def asScala(p: PH :: PTRepr): SH :: STRepr = {
      val head = hBinding.value.asScala(p.head)
      val tail = tBinding.value.asScala(p.tail)
      head :: tail
    }
  }

  implicit def coproductProtoBinding[SL, PL, SRRepr <: Coproduct, PRRepr <: Coproduct](
      implicit lBinding: Lazy[ProtoBinding[SL, PL]],
      rBinding: Lazy[ProtoBinding[SRRepr, PRRepr]]
  ): ProtoBinding[SL :+: SRRepr, PL :+: PRRepr] = new ProtoBinding[SL :+: SRRepr, PL :+: PRRepr] {
    override def asProto(s: SL :+: SRRepr): PL :+: PRRepr = s match {
      case Inl(head) => Inl(lBinding.value.asProto(head))
      case Inr(tail) => Inr(rBinding.value.asProto(tail))
    }
    override def asScala(p: PL :+: PRRepr): SL :+: SRRepr = p match {
      case Inl(head) => Inl(lBinding.value.asScala(head))
      case Inr(tail) => Inr(rBinding.value.asScala(tail))
    }
  }
}

sealed abstract class LowerPriorityImplicits {
  implicit def wrappedOneofProtoBinding[S, PV, PW](implicit pwGen: Lazy[Generic.Aux[PW, PV :: HNil]],
                                                   vBinding: Lazy[ProtoBinding[S, PV]]): ProtoBinding[S, PW] =
    new ProtoBinding[S, PW] {
      override def asProto(s: S): PW = pwGen.value.from(vBinding.value.asProto(s) :: HNil)
      override def asScala(p: PW): S = vBinding.value.asScala(pwGen.value.to(p).head)
    }
}
