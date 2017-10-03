package com.dhpcs.liquidity.model

import java.net.InetAddress
import java.time.Instant

import akka.actor
import akka.actor.ExtendedActorSystem
import akka.serialization.Serialization
import akka.typed.ActorRef
import akka.typed.scaladsl.adapter._
import com.dhpcs.liquidity.proto.binding.ProtoBinding

object ProtoBindings {

  implicit def setProtoBinding[S, P, C](implicit protoBinding: ProtoBinding[S, P, C]): ProtoBinding[Set[S], Seq[P], C] =
    ProtoBinding.instance(_.map(protoBinding.asProto).toSeq,
                          (set, system) => set.map(protoBinding.asScala(_)(system)).toSet)

  implicit final val MemberIdProtoBinding: ProtoBinding[MemberId, String, Any] =
    ProtoBinding.instance(_.id, (id, _) => MemberId(id))

  implicit final val AccountIdProtoBinding: ProtoBinding[AccountId, String, Any] =
    ProtoBinding.instance(_.id, (id, _) => AccountId(id))

  implicit final val TransactionIdProtoBinding: ProtoBinding[TransactionId, String, Any] =
    ProtoBinding.instance(_.id, (id, _) => TransactionId(id))

  implicit final val ZoneIdProtoBinding: ProtoBinding[ZoneId, String, Any] =
    ProtoBinding.instance(_.id.toString, (id, _) => ZoneId(id))

  implicit final val PublicKeyProtoBinding: ProtoBinding[PublicKey, com.google.protobuf.ByteString, Any] =
    ProtoBinding.instance(
      publicKey => com.google.protobuf.ByteString.copyFrom(publicKey.value.asByteBuffer()),
      (byteString, _) => PublicKey(okio.ByteString.of(byteString.asReadOnlyByteBuffer()))
    )

  implicit final val InetAddressProtoBinding: ProtoBinding[InetAddress, com.google.protobuf.ByteString, Any] =
    ProtoBinding.instance(
      inetAddress => com.google.protobuf.ByteString.copyFrom(inetAddress.getAddress),
      (inetAddress, _) => InetAddress.getByAddress(inetAddress.toByteArray)
    )

  implicit final val InstantProtoBinding: ProtoBinding[Instant, Long, Any] =
    ProtoBinding.instance(_.toEpochMilli, (timestamp, _) => Instant.ofEpochMilli(timestamp))

  implicit final val BigDecimalProtoBinding: ProtoBinding[BigDecimal, String, Any] =
    ProtoBinding.instance(
      _.toString(),
      (bigDecimal, _) => BigDecimal(bigDecimal)
    )

  trait EntityIdExtractor[E, I] {
    def extractId(entity: E): I
  }

  object EntityIdExtractor {
    def instance[E, I](apply: E => I): EntityIdExtractor[E, I] = new EntityIdExtractor[E, I] {
      override def extractId(e: E): I = apply(e)
    }
  }

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

  implicit final val UntypedActorRefProtoBinding: ProtoBinding[actor.ActorRef, String, ExtendedActorSystem] =
    ProtoBinding.instance(Serialization.serializedActorPath, (s, system) => system.provider.resolveActorRef(s))

  implicit def actorRefProtoBinding[A]: ProtoBinding[ActorRef[A], String, ExtendedActorSystem] =
    ProtoBinding.instance(actorRef => Serialization.serializedActorPath(actorRef.toUntyped),
                          (s, system) => system.provider.resolveActorRef(s))

  implicit def mapProtoBinding[SK, SV, PK, PV, C](
      implicit kBinding: ProtoBinding[SK, PK, C],
      vBinding: ProtoBinding[SV, PV, C]): ProtoBinding[Map[SK, SV], Map[PK, PV], C] =
    ProtoBinding.instance(
      _.map { case (sk, sv) => (kBinding.asProto(sk), vBinding.asProto(sv)) },
      (map, system) => map.map { case (pk, pv) => (kBinding.asScala(pk)(system), vBinding.asScala(pv)(system)) }
    )

}
