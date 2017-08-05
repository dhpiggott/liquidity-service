package com.dhpcs.liquidity

import java.util.UUID

import akka.actor.{ActorPath, ActorRef, ExtendedActorSystem}
import akka.serialization.Serialization
import com.dhpcs.liquidity.proto.binding.ProtoBinding

// TODO: Move all bindings into a ProtoBindings object
package object model {

  final val KeySize = 2048

  implicit def setProtoBinding[S, P, C](implicit protoBinding: ProtoBinding[S, P, C]): ProtoBinding[Set[S], Seq[P], C] =
    ProtoBinding.instance(_.map(protoBinding.asProto).toSeq,
                          (set, system) => set.map(protoBinding.asScala(_)(system)).toSet)

  implicit final val MemberIdProtoBinding: ProtoBinding[MemberId, Long, Any] =
    ProtoBinding.instance(_.id, (id, _) => MemberId(id))

  implicit final val AccountIdProtoBinding: ProtoBinding[AccountId, Long, Any] =
    ProtoBinding.instance(_.id, (id, _) => AccountId(id))

  implicit final val TransactionIdProtoBinding: ProtoBinding[TransactionId, Long, Any] =
    ProtoBinding.instance(_.id, (id, _) => TransactionId(id))

  implicit final val ZoneIdProtoBinding: ProtoBinding[ZoneId, String, Any] =
    ProtoBinding.instance(_.id.toString, (id, _) => ZoneId(UUID.fromString(id)))

  implicit final val PublicKeyProtoBinding: ProtoBinding[PublicKey, com.google.protobuf.ByteString, Any] =
    ProtoBinding.instance(
      publicKey => com.google.protobuf.ByteString.copyFrom(publicKey.value.asByteBuffer()),
      (byteString, _) => PublicKey(okio.ByteString.of(byteString.asReadOnlyByteBuffer()))
    )

  implicit final val BigDecimalProtoBinding: ProtoBinding[BigDecimal, proto.model.BigDecimal, Any] =
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

  implicit final val BigDecimalOptProtoBinding: ProtoBinding[BigDecimal, Option[proto.model.BigDecimal], Any] =
    ProtoBinding.instance(
      bigDecimal => Some(ProtoBinding[BigDecimal, proto.model.BigDecimal, Any].asProto(bigDecimal)), {
        case (None, _) => BigDecimal(0)
        case (Some(bigDecimal), system) =>
          ProtoBinding[BigDecimal, proto.model.BigDecimal, Any].asScala(bigDecimal)(system)
      }
    )

  implicit final val MemberIdExtractor: EntityIdExtractor[Member, MemberId] =
    EntityIdExtractor.instance[Member, MemberId](_.id)

  implicit final val AccountIdExtractor: EntityIdExtractor[Account, AccountId] =
    EntityIdExtractor.instance[Account, AccountId](_.id)

  implicit final val TransactionIdExtractor: EntityIdExtractor[Transaction, TransactionId] =
    EntityIdExtractor.instance[Transaction, TransactionId](_.id)

  implicit def idMapProtoBinding[SK, SV, P, C](
      implicit protoBinding: ProtoBinding[SV, P, C],
      entityIdExtractor: EntityIdExtractor[SV, SK]): ProtoBinding[Map[SK, SV], Seq[P], C] =
    ProtoBinding.instance(
      _.values.map(protoBinding.asProto).toSeq,
      (seq, system) => seq.map(protoBinding.asScala(_)(system)).map(s => entityIdExtractor.extractId(s) -> s).toMap)

  implicit final val ActorPathProtoBinding: ProtoBinding[ActorPath, String, Any] =
    ProtoBinding.instance(_.toSerializationFormat, (s, _) => ActorPath.fromString(s))

  implicit final val ActorRefProtoBinding: ProtoBinding[ActorRef, String, ExtendedActorSystem] =
    ProtoBinding.instance(Serialization.serializedActorPath, (s, system) => system.provider.resolveActorRef(s))

  implicit def mapProtoBinding[SK, SV, PK, PV, C](
      implicit kBinding: ProtoBinding[SK, PK, C],
      vBinding: ProtoBinding[SV, PV, C]): ProtoBinding[Map[SK, SV], Map[PK, PV], C] =
    ProtoBinding.instance(
      _.map { case (sk, sv) => (kBinding.asProto(sk), vBinding.asProto(sv)) },
      (map, system) => map.map { case (pk, pv) => (kBinding.asScala(pk)(system), vBinding.asScala(pv)(system)) }
    )

}
