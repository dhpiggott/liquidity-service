package com.dhpcs.liquidity.server

import java.net.InetAddress

import com.dhpcs.liquidity.model._
import doobie._
import okio.ByteString
import scalapb.json4s.JsonFormat

object SqlBindings {

  implicit val PublicKeyMeta: Meta[PublicKey] =
    Meta[Array[Byte]]
      .xmap(bytes => PublicKey(ByteString.of(bytes: _*)), _.value.toByteArray)

  implicit val InetAddressMeta: Meta[InetAddress] =
    Meta[String].xmap(InetAddress.getByName, _.getHostAddress)

  implicit val ZoneIdMeta: Meta[ZoneId] = Meta[String].xmap(ZoneId(_), _.value)

  implicit val MemberIdMeta: Meta[MemberId] =
    Meta[String].xmap(MemberId, _.value)

  implicit val AccountIdMeta: Meta[AccountId] =
    Meta[String].xmap(AccountId, _.value)

  implicit val TransactionIdMeta: Meta[TransactionId] =
    Meta[String].xmap(TransactionId, _.value)

  implicit val StructMeta: Meta[com.google.protobuf.struct.Struct] =
    Meta[String].xmap(
      JsonFormat.fromJsonString[com.google.protobuf.struct.Struct],
      JsonFormat.toJsonString[com.google.protobuf.struct.Struct])

}
