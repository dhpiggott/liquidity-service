package com.dhpcs.liquidity

import java.util.UUID

import com.dhpcs.liquidity.serialization.ProtoConverter
import play.api.libs.json._

package object model {

  implicit final val JsObjectProtoConverter: ProtoConverter[JsObject, com.google.protobuf.struct.Struct] =
    ProtoConverter.instance(
      jsObject =>
        com.google.protobuf.struct
          .Struct(jsObject.value.mapValues(ProtoConverter[JsValue, com.google.protobuf.struct.Value].asProto).toMap),
      struct => JsObject(struct.fields.mapValues(ProtoConverter[JsValue, com.google.protobuf.struct.Value].asScala))
    )

  implicit final val BigDecimalProtoConverter: ProtoConverter[BigDecimal, Option[proto.model.BigDecimal]] =
    ProtoConverter.instance(
      bigDecimal =>
        Some(
          proto.model.BigDecimal(
            bigDecimal.scale,
            com.google.protobuf.ByteString.copyFrom(bigDecimal.underlying().unscaledValue().toByteArray)
          )), {
        case None => BigDecimal(0)
        case Some(bigDecimal) =>
          BigDecimal(
            BigInt(bigDecimal.value.toByteArray),
            bigDecimal.scale
          )
      }
    )

  implicit final val PublicKeyProtoConverter: ProtoConverter[PublicKey, com.google.protobuf.ByteString] =
    ProtoConverter.instance(
      publicKey => com.google.protobuf.ByteString.copyFrom(publicKey.value.asByteBuffer()),
      byteString => PublicKey(okio.ByteString.of(byteString.asReadOnlyByteBuffer()))
    )

  implicit final val MemberIdProtoConverter: ProtoConverter[MemberId, Long] =
    ProtoConverter.instance(_.id, MemberId(_))

  implicit final val AccountIdProtoConverter: ProtoConverter[AccountId, Long] =
    ProtoConverter.instance(_.id, AccountId(_))

  implicit final val TransactionIdProtoConverter: ProtoConverter[TransactionId, Long] =
    ProtoConverter.instance(_.id, TransactionId(_))

  implicit def setProtoConverter[S, P](implicit protoConverter: ProtoConverter[S, P]): ProtoConverter[Set[S], Seq[P]] =
    ProtoConverter.instance(_.map(protoConverter.asProto).toSeq, _.map(protoConverter.asScala).toSet)

  implicit final val ZoneIdProtoConverter: ProtoConverter[ZoneId, String] =
    ProtoConverter.instance(_.id.toString, id => ZoneId(UUID.fromString(id)))

  implicit final val MemberIdExtractor: EntityIdExtractor[Member, MemberId] =
    EntityIdExtractor.instance[Member, MemberId](_.id)

  implicit final val AccountIdExtractor: EntityIdExtractor[Account, AccountId] =
    EntityIdExtractor.instance[Account, AccountId](_.id)

  implicit final val TransactionIdExtractor: EntityIdExtractor[Transaction, TransactionId] =
    EntityIdExtractor.instance[Transaction, TransactionId](_.id)

  implicit def idMapProtoConverter[SK, SV, P](
      implicit protoConverter: ProtoConverter[SV, P],
      entityIdExtractor: EntityIdExtractor[SV, SK]): ProtoConverter[Map[SK, SV], Seq[P]] =
    ProtoConverter.instance(_.values.map(protoConverter.asProto).toSeq,
                            _.map(protoConverter.asScala).map(s => entityIdExtractor.extractId(s) -> s).toMap)

}
