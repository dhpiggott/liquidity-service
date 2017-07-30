package com.dhpcs.liquidity

import java.util.UUID

import akka.actor.{ActorPath, ActorRef}
import akka.serialization.Serialization
import com.dhpcs.liquidity.proto.binding.ProtoBinding

// TODO: Move all bindings into a ProtoBindings object
package object model {

  final val KeySize = 2048

  implicit def setProtoBinding[S, P](implicit protoConverter: ProtoBinding[S, P]): ProtoBinding[Set[S], Seq[P]] =
    ProtoBinding.instance(_.map(protoConverter.asProto).toSeq,
                          (set, system) => set.map(protoConverter.asScala(_)(system)).toSet)

  implicit final val MemberIdProtoBinding: ProtoBinding[MemberId, Long] =
    ProtoBinding.instance(_.id, (id, _) => MemberId(id))

  implicit final val AccountIdProtoBinding: ProtoBinding[AccountId, Long] =
    ProtoBinding.instance(_.id, (id, _) => AccountId(id))

  implicit final val TransactionIdProtoBinding: ProtoBinding[TransactionId, Long] =
    ProtoBinding.instance(_.id, (id, _) => TransactionId(id))

  implicit final val ZoneIdProtoBinding: ProtoBinding[ZoneId, String] =
    ProtoBinding.instance(_.id.toString, (id, _) => ZoneId(UUID.fromString(id)))

  implicit final val PublicKeyProtoBinding: ProtoBinding[PublicKey, com.google.protobuf.ByteString] =
    ProtoBinding.instance(
      publicKey => com.google.protobuf.ByteString.copyFrom(publicKey.value.asByteBuffer()),
      (byteString, _) => PublicKey(okio.ByteString.of(byteString.asReadOnlyByteBuffer()))
    )

  implicit final val BigDecimalProtoBinding: ProtoBinding[BigDecimal, proto.model.BigDecimal] =
    ProtoBinding.instance(
      bigDecimal =>
        proto.model.BigDecimal(
          bigDecimal.scale,
          com.google.protobuf.ByteString.copyFrom(bigDecimal.underlying().unscaledValue().toByteArray)
      ),
      (bigDecimal, _) =>
        BigDecimal(
          BigInt(bigDecimal.value.toByteArray),
          bigDecimal.scale
      )
    )

  implicit final val BigDecimalOptProtoBinding: ProtoBinding[BigDecimal, Option[proto.model.BigDecimal]] =
    ProtoBinding.instance(
      bigDecimal => Some(ProtoBinding[BigDecimal, proto.model.BigDecimal].asProto(bigDecimal)), {
        case (None, _) => BigDecimal(0)
        case (Some(bigDecimal), system) =>
          ProtoBinding[BigDecimal, proto.model.BigDecimal].asScala(bigDecimal)(system)
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
    ProtoBinding.instance(
      _.values.map(protoConverter.asProto).toSeq,
      (seq, system) => seq.map(protoConverter.asScala(_)(system)).map(s => entityIdExtractor.extractId(s) -> s).toMap)

  implicit final val ActorPathProtoBinding: ProtoBinding[ActorPath, String] =
    ProtoBinding.instance(_.toSerializationFormat, (s, _) => ActorPath.fromString(s))

  implicit final val ActorRefProtoBinding: ProtoBinding[ActorRef, String] =
    ProtoBinding.instance(Serialization.serializedActorPath, (s, system) => system.provider.resolveActorRef(s))

  implicit def mapProtoBinding[SK, SV, PK, PV](implicit kBinding: ProtoBinding[SK, PK],
                                               vBinding: ProtoBinding[SV, PV]): ProtoBinding[Map[SK, SV], Map[PK, PV]] =
    ProtoBinding.instance(
      _.map { case (sk, sv) => (kBinding.asProto(sk), vBinding.asProto(sv)) },
      (map, system) => map.map { case (pk, pv) => (kBinding.asScala(pk)(system), vBinding.asScala(pv)(system)) }
    )

}
