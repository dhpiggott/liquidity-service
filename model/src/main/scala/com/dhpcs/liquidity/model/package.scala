package com.dhpcs.liquidity

import java.util.UUID

import com.dhpcs.liquidity.proto.binding.ProtoBinding

// TODO: Move all bindings into a ProtoBindings object
package object model {

  final val KeySize = 2048

  implicit def setProtoBinding[S, P](implicit protoConverter: ProtoBinding[S, P]): ProtoBinding[Set[S], Seq[P]] =
    ProtoBinding.instance(_.map(protoConverter.asProto).toSeq, _.map(protoConverter.asScala).toSet)

  implicit final val MemberIdProtoBinding: ProtoBinding[MemberId, Long] =
    ProtoBinding.instance(_.id, MemberId)

  implicit final val AccountIdProtoBinding: ProtoBinding[AccountId, Long] =
    ProtoBinding.instance(_.id, AccountId)

  implicit final val TransactionIdProtoBinding: ProtoBinding[TransactionId, Long] =
    ProtoBinding.instance(_.id, TransactionId)

  implicit final val ZoneIdProtoBinding: ProtoBinding[ZoneId, String] =
    ProtoBinding.instance(_.id.toString, id => ZoneId(UUID.fromString(id)))

  implicit final val PublicKeyProtoBinding: ProtoBinding[PublicKey, com.google.protobuf.ByteString] =
    ProtoBinding.instance(
      publicKey => com.google.protobuf.ByteString.copyFrom(publicKey.value.asByteBuffer()),
      byteString => PublicKey(okio.ByteString.of(byteString.asReadOnlyByteBuffer()))
    )

  implicit final val BigDecimalProtoBinding: ProtoBinding[BigDecimal, proto.model.BigDecimal] =
    ProtoBinding.instance(
      bigDecimal =>
        proto.model.BigDecimal(
          bigDecimal.scale,
          com.google.protobuf.ByteString.copyFrom(bigDecimal.underlying().unscaledValue().toByteArray)
      ),
      bigDecimal =>
        BigDecimal(
          BigInt(bigDecimal.value.toByteArray),
          bigDecimal.scale
      )
    )

  implicit final val BigDecimalOptProtoBinding: ProtoBinding[BigDecimal, Option[proto.model.BigDecimal]] =
    ProtoBinding.instance(
      bigDecimal => Some(ProtoBinding[BigDecimal, proto.model.BigDecimal].asProto(bigDecimal)), {
        case None => BigDecimal(0)
        case Some(bigDecimal) =>
          ProtoBinding[BigDecimal, proto.model.BigDecimal].asScala(bigDecimal)
      }
    )

  implicit final val MemberIdExtractor: EntityIdExtractor[Member, MemberId] =
    EntityIdExtractor.instance[Member, MemberId](_.id)

  implicit final val AccountIdExtractor: EntityIdExtractor[Account, AccountId] =
    EntityIdExtractor.instance[Account, AccountId](_.id)

  implicit final val TransactionIdExtractor: EntityIdExtractor[Transaction, TransactionId] =
    EntityIdExtractor.instance[Transaction, TransactionId](_.id)

  implicit def idMapProtoBinding[SK, SV, P](
      implicit protoConverter: ProtoBinding[SV, P],
      entityIdExtractor: EntityIdExtractor[SV, SK]): ProtoBinding[Map[SK, SV], Seq[P]] =
    ProtoBinding.instance(_.values.map(protoConverter.asProto).toSeq,
                          _.map(protoConverter.asScala).map(s => entityIdExtractor.extractId(s) -> s).toMap)

  implicit def mapProtoBinding[SK, SV, PK, PV](implicit kBinding: ProtoBinding[SK, PK],
                                               vBinding: ProtoBinding[SV, PV]): ProtoBinding[Map[SK, SV], Map[PK, PV]] =
    ProtoBinding.instance(
      _.map { case (sk, sv) => (kBinding.asProto(sk), vBinding.asProto(sv)) },
      _.map { case (pk, pv) => (kBinding.asScala(pk), vBinding.asScala(pv)) }
    )

}
