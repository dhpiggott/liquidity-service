package com.dhpcs.liquidity.proto.binding

import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, Validated, ValidatedNel}
import shapeless._

import scala.reflect.ClassTag

object ProtoBindings extends LowPriorityImplicits {

  implicit def identityProtoBinding[A, C]: ProtoBinding[A, A, C] =
    ProtoBinding.instance((a, _) => a, (a, _) => a)

  implicit def nonEmptyListBinding[S, PV, PW, C](
      implicit pwGen: Lazy[Generic.Aux[PW, Seq[PV] :: HNil]],
      vBinding: Lazy[ProtoBinding[S, PV, C]]
  ): ProtoBinding[NonEmptyList[S], PW, C] = new ProtoBinding[NonEmptyList[S], PW, C] {
    override def asProto(nonEmptyList: NonEmptyList[S])(implicit c: C): PW =
      pwGen.value.from(nonEmptyList.toList.map(vBinding.value.asProto) :: HNil)
    override def asScala(p: PW)(implicit c: C): NonEmptyList[S] =
      NonEmptyList.fromListUnsafe(pwGen.value.to(p).head.toList.map(vBinding.value.asScala))
  }

  implicit def validatedNelProtoBinding[SE, SA, PEW, PA, PW, PEmpty, C](
      implicit pwGen: Lazy[Generic.Aux[PW, PEmpty :+: PEW :+: PA :+: CNil]],
      eBinding: Lazy[ProtoBinding[NonEmptyList[SE], PEW, C]],
      aBinding: Lazy[ProtoBinding[SA, PA, C]]
  ): ProtoBinding[ValidatedNel[SE, SA], PW, C] = new ProtoBinding[ValidatedNel[SE, SA], PW, C] {
    override def asProto(validated: ValidatedNel[SE, SA])(implicit c: C): PW = validated match {
      case Invalid(e) => pwGen.value.from(Inr(Inl(eBinding.value.asProto(e))))
      case Valid(a)   => pwGen.value.from(Inr(Inr(Inl(aBinding.value.asProto(a)))))
    }
    override def asScala(p: PW)(implicit c: C): ValidatedNel[SE, SA] = pwGen.value.to(p) match {
      case Inl(_)            => throw new IllegalArgumentException("Empty or unsupported result")
      case Inr(Inl(errors))  => Validated.invalid(eBinding.value.asScala(errors))
      case Inr(Inr(Inl(pv))) => Validated.valid(aBinding.value.asScala(pv))
      case Inr(Inr(Inr(_)))  => throw new IllegalArgumentException("Inconceivable")
    }
  }
}

sealed abstract class LowPriorityImplicits extends LowerPriorityImplicits {

  implicit def genericProtoBinding[S, P, SRepr, PRepr, C](
      implicit sGen: Generic.Aux[S, SRepr],
      pGen: Generic.Aux[P, PRepr],
      genericBinding: Lazy[ProtoBinding[SRepr, PRepr, C]]
  ): ProtoBinding[S, P, C] = new ProtoBinding[S, P, C] {
    override def asProto(s: S)(implicit c: C): P = {
      val gen  = sGen.to(s)
      val repr = genericBinding.value.asProto(gen)
      pGen.from(repr)
    }
    override def asScala(p: P)(implicit c: C): S = {
      val gen  = pGen.to(p)
      val repr = genericBinding.value.asScala(gen)
      sGen.from(repr)
    }
  }

  implicit def optionProtoBinding[S, P, C](
      implicit protoBinding: ProtoBinding[S, P, C],
      protoClassTag: ClassTag[P]
  ): ProtoBinding[S, Option[P], C] = new ProtoBinding[S, Option[P], C] {
    override def asProto(s: S)(implicit c: C): Option[P] =
      Some(protoBinding.asProto(s))
    override def asScala(maybeP: Option[P])(implicit c: C): S =
      protoBinding.asScala(
        maybeP.getOrElse(throw new IllegalArgumentException(s"Empty ${protoClassTag.runtimeClass.getName}"))
      )
  }

  implicit def hlistProtoBinding[SH, PH, STRepr <: HList, PTRepr <: HList, C](
      implicit hBinding: Lazy[ProtoBinding[SH, PH, C]],
      tBinding: Lazy[ProtoBinding[STRepr, PTRepr, C]]
  ): ProtoBinding[SH :: STRepr, PH :: PTRepr, C] = new ProtoBinding[SH :: STRepr, PH :: PTRepr, C] {
    override def asProto(s: SH :: STRepr)(implicit c: C): PH :: PTRepr = {
      val head = hBinding.value.asProto(s.head)
      val tail = tBinding.value.asProto(s.tail)
      head :: tail
    }
    override def asScala(p: PH :: PTRepr)(implicit c: C): SH :: STRepr = {
      val head = hBinding.value.asScala(p.head)
      val tail = tBinding.value.asScala(p.tail)
      head :: tail
    }
  }

  implicit def coproductProtoBinding[SL, PL, SRRepr <: Coproduct, PRRepr <: Coproduct, C](
      implicit lBinding: Lazy[ProtoBinding[SL, PL, C]],
      rBinding: Lazy[ProtoBinding[SRRepr, PRRepr, C]]
  ): ProtoBinding[SL :+: SRRepr, PL :+: PRRepr, C] = new ProtoBinding[SL :+: SRRepr, PL :+: PRRepr, C] {
    override def asProto(s: SL :+: SRRepr)(implicit c: C): PL :+: PRRepr = s match {
      case Inl(head) => Inl(lBinding.value.asProto(head))
      case Inr(tail) => Inr(rBinding.value.asProto(tail))
    }
    override def asScala(p: PL :+: PRRepr)(implicit c: C): SL :+: SRRepr = p match {
      case Inl(head) => Inl(lBinding.value.asScala(head))
      case Inr(tail) => Inr(rBinding.value.asScala(tail))
    }
  }
}

sealed abstract class LowerPriorityImplicits {
  implicit def wrappedOneofProtoBinding[S, PV, PW, C](implicit pwGen: Lazy[Generic.Aux[PW, PV :: HNil]],
                                                      vBinding: Lazy[ProtoBinding[S, PV, C]]): ProtoBinding[S, PW, C] =
    new ProtoBinding[S, PW, C] {
      override def asProto(s: S)(implicit c: C): PW = pwGen.value.from(vBinding.value.asProto(s) :: HNil)
      override def asScala(p: PW)(implicit c: C): S =
        vBinding.value.asScala(pwGen.value.to(p).head)
    }
}
