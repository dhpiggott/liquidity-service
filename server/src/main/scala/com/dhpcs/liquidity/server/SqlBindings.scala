package com.dhpcs.liquidity.server

import java.net.InetAddress

import com.dhpcs.liquidity.model._
import doobie._
import okio.ByteString
import scalapb.json4s.JsonFormat

object SqlBindings {

  implicit val PublicKeyMeta: Meta[PublicKey] =
    Meta[Array[Byte]]
      .timap(bytes => PublicKey(ByteString.of(bytes: _*)))(_.value.toByteArray)

  implicit val InetAddressMeta: Meta[InetAddress] =
    Meta[String].timap(InetAddress.getByName)(_.getHostAddress)

  implicit val ZoneIdMeta: Meta[ZoneId] = Meta[String].timap(ZoneId(_))(_.value)

  implicit val MemberIdMeta: Meta[MemberId] =
    Meta[String].timap(MemberId)(_.value)

  implicit val AccountIdMeta: Meta[AccountId] =
    Meta[String].timap(AccountId)(_.value)

  implicit val TransactionIdMeta: Meta[TransactionId] =
    Meta[String].timap(TransactionId)(_.value)

  implicit val StructMeta: Meta[com.google.protobuf.struct.Struct] =
    Meta[String].timap(
      JsonFormat.fromJsonString[com.google.protobuf.struct.Struct])(
      JsonFormat.toJsonString[com.google.protobuf.struct.Struct])

  implicit val BigDecimalMeta: Meta[BigDecimal] =
    Meta[String].timap(BigDecimal(_))(_.toString())

}
