package com.dhpcs.liquidity.model

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.util.UUID

import com.dhpcs.liquidity.model.ModelSpec._
import com.dhpcs.liquidity.model.ProtoBindings._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.proto.model
import org.scalatest.FreeSpec

object ModelSpec {

  val (rsaPrivateKey: RSAPrivateKey, rsaPublicKey: RSAPublicKey) = {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(2048)
    val keyPair = keyPairGenerator.generateKeyPair
    (keyPair.getPrivate, keyPair.getPublic)
  }

  val zone: Zone = Zone(
    id = ZoneId(
      UUID.fromString("b0c608d4-22f5-460e-8872-15a10d79daf2")
    ),
    equityAccountId = AccountId(0L),
    members = Map(
      MemberId(0L) ->
        Member(id = MemberId(0L), ownerPublicKeys = Set(PublicKey(rsaPublicKey.getEncoded)), name = Some("Banker")),
      MemberId(1L) ->
        Member(id = MemberId(1L), ownerPublicKeys = Set(PublicKey(rsaPublicKey.getEncoded)), name = Some("Dave"))
    ),
    accounts = Map(
      AccountId(0L) ->
        Account(id = AccountId(0L), ownerMemberIds = Set(MemberId(0L)), name = Some("Bank")),
      AccountId(1L) ->
        Account(id = AccountId(1L), ownerMemberIds = Set(MemberId(1L)), name = Some("Dave's account"))
    ),
    transactions = Map(
      TransactionId(0L) ->
        Transaction(
          id = TransactionId(0L),
          from = AccountId(0L),
          to = AccountId(1L),
          value = BigDecimal(1000000),
          creator = MemberId(0L),
          created = 1433611420487L,
          description = Some("Dave's lottery win")
        )
    ),
    created = 1433611420487L,
    expires = 1433611420487L,
    name = Some("Dave's zone"),
    metadata = Some(
      com.google.protobuf.struct.Struct(
        Map(
          "currency" -> com.google.protobuf.struct.Value(com.google.protobuf.struct.Value.Kind.StringValue("GBP"))
        )))
  )

  //noinspection RedundantDefaultArgument
  val zoneProto: model.Zone = proto.model.Zone(
    id = "b0c608d4-22f5-460e-8872-15a10d79daf2",
    equityAccountId = 0L,
    members = Seq(
      proto.model.Member(id = 0L,
                         ownerPublicKeys = Seq(com.google.protobuf.ByteString.copyFrom(rsaPublicKey.getEncoded)),
                         name = Some("Banker")),
      proto.model.Member(id = 1L,
                         ownerPublicKeys = Seq(com.google.protobuf.ByteString.copyFrom(rsaPublicKey.getEncoded)),
                         name = Some("Dave"))
    ),
    accounts = Seq(
      proto.model.Account(id = 0L, ownerMemberIds = Seq(0L), name = Some("Bank")),
      proto.model.Account(id = 1L, ownerMemberIds = Seq(1L), name = Some("Dave's account"))
    ),
    transactions = Seq(
      proto.model.Transaction(
        id = 0L,
        from = 0L,
        to = 1L,
        value = Some(
          proto.model.BigDecimal(scale = 0,
                                 value = com.google.protobuf.ByteString.copyFrom(BigInt(1000000).toByteArray))),
        creator = 0L,
        created = 1433611420487L,
        description = Some("Dave's lottery win")
      )
    ),
    created = 1433611420487L,
    expires = 1433611420487L,
    name = Some("Dave's zone"),
    metadata = Some(
      com.google.protobuf.struct.Struct(
        Map(
          "currency" -> com.google.protobuf.struct.Value(com.google.protobuf.struct.Value.Kind.StringValue("GBP"))
        )))
  )

}

class ModelSpec extends FreeSpec {

  "A Zone" - {
    s"will convert to $zoneProto" in assert(
      ProtoBinding[Zone, proto.model.Zone, Any].asProto(zone) === zoneProto
    )
    s"will convert from $zone" in assert(
      ProtoBinding[Zone, proto.model.Zone, Any].asScala(zoneProto)(()) === zone
    )
  }
}
