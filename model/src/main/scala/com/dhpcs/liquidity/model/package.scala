package com.dhpcs.liquidity

import java.util.UUID

import play.api.libs.json._

package object model {

  implicit class RichPublicKey(val publicKey: PublicKey) extends AnyVal {
    def asProto: com.google.protobuf.ByteString =
      com.google.protobuf.ByteString.copyFrom(publicKey.value.asByteBuffer())
  }

  implicit class RichProtobufPublicKey(val publicKey: com.google.protobuf.ByteString) extends AnyVal {
    def asScala: PublicKey = PublicKey(okio.ByteString.of(publicKey.asReadOnlyByteBuffer()))
  }

  implicit class RichJsObject(val jsObject: JsObject) extends AnyVal {
    def asProto: com.google.protobuf.struct.Struct =
      com.google.protobuf.struct.Struct(jsObject.value.mapValues(_.asProto).toMap)
  }

  implicit class RichProtobufStruct(val struct: com.google.protobuf.struct.Struct) extends AnyVal {
    def asScala: JsObject =
      JsObject(struct.fields.mapValues(_.asScala))
  }

  implicit class RichJsValue(val jsValue: JsValue) extends AnyVal {
    def asProto: com.google.protobuf.struct.Value =
      com.google.protobuf.struct.Value(jsValue match {
        case JsNull           => com.google.protobuf.struct.Value.Kind.NullValue(com.google.protobuf.struct.NullValue.NULL_VALUE)
        case JsBoolean(value) => com.google.protobuf.struct.Value.Kind.BoolValue(value)
        case JsNumber(value)  => com.google.protobuf.struct.Value.Kind.NumberValue(value.doubleValue())
        case JsString(value)  => com.google.protobuf.struct.Value.Kind.StringValue(value)
        case JsArray(value) =>
          com.google.protobuf.struct.Value.Kind
            .ListValue(com.google.protobuf.struct.ListValue(value.map(_.asProto)))
        case JsObject(value) =>
          com.google.protobuf.struct.Value.Kind
            .StructValue(com.google.protobuf.struct.Struct(value.mapValues(_.asProto).toMap))
      })
  }

  implicit class RichProtobufValue(val value: com.google.protobuf.struct.Value) extends AnyVal {
    def asScala: JsValue = value.kind match {
      case com.google.protobuf.struct.Value.Kind.Empty                    => throw new IllegalArgumentException("Empty Kind")
      case com.google.protobuf.struct.Value.Kind.NullValue(_)             => JsNull
      case com.google.protobuf.struct.Value.Kind.NumberValue(numberValue) => JsNumber(numberValue)
      case com.google.protobuf.struct.Value.Kind.StringValue(stringValue) => JsString(stringValue)
      case com.google.protobuf.struct.Value.Kind.BoolValue(boolValue)     => JsBoolean(boolValue)
      case com.google.protobuf.struct.Value.Kind.StructValue(structValue) =>
        JsObject(structValue.fields.mapValues(_.asScala))
      case com.google.protobuf.struct.Value.Kind.ListValue(listValue) =>
        JsArray(listValue.values.map(_.asScala))
    }
  }

  implicit class RichBigDecimal(val bigDecimal: BigDecimal) extends AnyVal {
    def asProto: proto.model.BigDecimal = proto.model.BigDecimal(
      bigDecimal.scale,
      com.google.protobuf.ByteString.copyFrom(bigDecimal.underlying().unscaledValue().toByteArray)
    )
  }

  implicit class RichProtobufBigDecimal(val bigDecimal: proto.model.BigDecimal) extends AnyVal {
    def asScala: BigDecimal = BigDecimal(
      BigInt(bigDecimal.value.toByteArray),
      bigDecimal.scale
    )
  }

  implicit class RichMember(val member: Member) extends AnyVal {
    def asProto: proto.model.Member = proto.model.Member(
      member.id.id,
      member.ownerPublicKey.asProto,
      member.name,
      member.metadata.map(_.asProto)
    )
  }

  implicit class RichProtobufMember(val member: proto.model.Member) extends AnyVal {
    def asScala: Member = Member(
      id = MemberId(member.id),
      ownerPublicKey = PublicKey(okio.ByteString.of(member.ownerPublicKey.asReadOnlyByteBuffer())),
      name = member.name,
      metadata = member.metadata.map(_.asScala)
    )
  }

  implicit class RichAccount(val account: Account) extends AnyVal {
    def asProto: proto.model.Account = proto.model.Account(
      account.id.id,
      account.ownerMemberIds.map(_.id).toSeq,
      account.name,
      account.metadata.map(_.asProto)
    )
  }

  implicit class RichProtobufAccount(val account: proto.model.Account) extends AnyVal {
    def asScala: Account = Account(
      id = AccountId(account.id),
      ownerMemberIds = account.ownerMemberIds.map(MemberId(_)).toSet,
      name = account.name,
      metadata = account.metadata.map(_.asScala)
    )
  }

  implicit class RichTransaction(val transaction: Transaction) extends AnyVal {
    def asProto: proto.model.Transaction = proto.model.Transaction(
      transaction.id.id,
      transaction.from.id,
      transaction.to.id,
      Some(transaction.value.asProto),
      transaction.creator.id,
      transaction.created,
      transaction.description,
      transaction.metadata.map(_.asProto)
    )
  }

  implicit class RichProtobufTransaction(val transaction: proto.model.Transaction) extends AnyVal {
    def asScala: Transaction = Transaction(
      id = TransactionId(transaction.id),
      from = AccountId(transaction.from),
      to = AccountId(transaction.to),
      value = transaction.value.map(_.asScala).getOrElse(java.math.BigDecimal.ZERO),
      creator = MemberId(transaction.creator),
      created = transaction.created,
      description = transaction.description,
      metadata = transaction.metadata.map(_.asScala)
    )
  }

  implicit class RichZone(val zone: Zone) extends AnyVal {
    def asProto: proto.model.Zone =
      proto.model.Zone(
        zone.id.id.toString,
        zone.equityAccountId.id,
        zone.members.values.map(_.asProto).toSeq,
        zone.accounts.values.map(_.asProto).toSeq,
        zone.transactions.values.map(_.asProto).toSeq,
        zone.created,
        zone.expires,
        zone.name,
        zone.metadata.map(_.asProto)
      )
  }

  implicit class RichProtobufZone(val zone: proto.model.Zone) extends AnyVal {
    def asScala: Zone =
      Zone(
        id = ZoneId(UUID.fromString(zone.id)),
        equityAccountId = AccountId(zone.equityAccountId),
        members = zone.members.map(_.asScala).map(member => member.id     -> member).toMap,
        accounts = zone.accounts.map(_.asScala).map(account => account.id -> account).toMap,
        transactions = zone.transactions
          .map(_.asScala)
          .map(transaction => transaction.id -> transaction)
          .toMap,
        created = zone.created,
        expires = zone.expires,
        name = zone.name,
        metadata = zone.metadata.map(_.asScala)
      )
  }
}
