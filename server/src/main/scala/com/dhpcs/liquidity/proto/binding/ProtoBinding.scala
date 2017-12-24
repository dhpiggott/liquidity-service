package com.dhpcs.liquidity.proto.binding

object ProtoBinding {

  def instance[S, P, C](apply: (S, C) => P,
                        unapply: (P, C) => S): ProtoBinding[S, P, C] =
    new ProtoBinding[S, P, C] {
      override def asProto(s: S)(implicit c: C): P = apply(s, c)
      override def asScala(p: P)(implicit c: C): S = unapply(p, c)
    }

  def apply[S, P, C](
      implicit protoBinding: ProtoBinding[S, P, C]): ProtoBinding[S, P, C] =
    protoBinding

}

trait ProtoBinding[S, P, -C] {
  def asProto(s: S)(implicit c: C): P
  def asScala(p: P)(implicit c: C): S
}
