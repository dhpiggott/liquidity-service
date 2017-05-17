package com.dhpcs.liquidity

import java.util.UUID

import com.dhpcs.liquidity.serialization.ProtoConverter
import play.api.libs.json._

package object model {

  implicit final lazy val ProtobufStructFormat: OFormat[com.google.protobuf.struct.Struct] = OFormat(
    (jsValue: JsValue) =>
      jsValue
        .validate[Map[String, com.google.protobuf.struct.Value]]
        .map(com.google.protobuf.struct.Struct(_)),
    (struct: com.google.protobuf.struct.Struct) => JsObject(struct.fields.mapValues(Json.toJson(_)))
  )

  implicit final lazy val ProtobufValueFormat: Format[com.google.protobuf.struct.Value] = Format(
    {
      case JsNull =>
        JsSuccess(
          com.google.protobuf.struct.Value(
            com.google.protobuf.struct.Value.Kind.NullValue(com.google.protobuf.struct.NullValue.NULL_VALUE)
          )
        )
      case JsBoolean(value) =>
        JsSuccess(
          com.google.protobuf.struct.Value(
            com.google.protobuf.struct.Value.Kind.BoolValue(value)
          )
        )
      case JsNumber(value) =>
        JsSuccess(
          com.google.protobuf.struct.Value(
            com.google.protobuf.struct.Value.Kind.NumberValue(value.doubleValue())
          )
        )
      case JsString(value) =>
        JsSuccess(com.google.protobuf.struct.Value(com.google.protobuf.struct.Value.Kind.StringValue(value)))
      case jsArray: JsArray =>
        jsArray
          .validate[Seq[com.google.protobuf.struct.Value]]
          .map(
            value =>
              com.google.protobuf.struct.Value(
                com.google.protobuf.struct.Value.Kind
                  .ListValue(com.google.protobuf.struct.ListValue(value))
            ))
      case jsObject: JsObject =>
        jsObject
          .validate[Map[String, com.google.protobuf.struct.Value]]
          .map(
            value =>
              com.google.protobuf.struct.Value(
                com.google.protobuf.struct.Value.Kind
                  .StructValue(com.google.protobuf.struct.Struct(value))
            ))
    },
    _.kind match {
      case com.google.protobuf.struct.Value.Kind.Empty =>
        throw new IllegalArgumentException("Empty Kind")
      case com.google.protobuf.struct.Value.Kind.NullValue(_) =>
        JsNull
      case com.google.protobuf.struct.Value.Kind.NumberValue(numberValue) =>
        JsNumber(numberValue)
      case com.google.protobuf.struct.Value.Kind.StringValue(stringValue) =>
        JsString(stringValue)
      case com.google.protobuf.struct.Value.Kind.BoolValue(boolValue) =>
        JsBoolean(boolValue)
      case com.google.protobuf.struct.Value.Kind.StructValue(structValue) =>
        JsObject(structValue.fields.mapValues(Json.toJson(_)))
      case com.google.protobuf.struct.Value.Kind.ListValue(listValue) =>
        JsArray(listValue.values.map(Json.toJson(_)))
    }
  )

  implicit def setProtoConverter[S, P](implicit protoConverter: ProtoConverter[S, P]): ProtoConverter[Set[S], Seq[P]] =
    ProtoConverter.instance(_.map(protoConverter.asProto).toSeq, _.map(protoConverter.asScala).toSet)

  implicit final val MemberIdProtoConverter: ProtoConverter[MemberId, Long] =
    ProtoConverter.instance(_.id, MemberId(_))

  implicit final val AccountIdProtoConverter: ProtoConverter[AccountId, Long] =
    ProtoConverter.instance(_.id, AccountId(_))

  implicit final val TransactionIdProtoConverter: ProtoConverter[TransactionId, Long] =
    ProtoConverter.instance(_.id, TransactionId(_))

  implicit final val ZoneIdProtoConverter: ProtoConverter[ZoneId, String] =
    ProtoConverter.instance(_.id.toString, id => ZoneId(UUID.fromString(id)))

  implicit final val PublicKeyProtoConverter: ProtoConverter[PublicKey, com.google.protobuf.ByteString] =
    ProtoConverter.instance(
      publicKey => com.google.protobuf.ByteString.copyFrom(publicKey.value.asByteBuffer()),
      byteString => PublicKey(okio.ByteString.of(byteString.asReadOnlyByteBuffer()))
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
