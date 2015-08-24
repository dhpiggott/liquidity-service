package com.dhpcs.liquidity.models

import java.security.KeyPairGenerator

import com.dhpcs.json.FormatBehaviors
import com.google.common.io.BaseEncoding
import org.scalatest._
import play.api.data.validation.ValidationError
import play.api.libs.json._

class ZoneSpec extends FunSpec with FormatBehaviors[Zone] with Matchers {

  describe("A JsValue of the wrong type") {
    it should behave like readError(
      Json.parse( """0"""),
      JsError(List(
        (__ \ "equityAccountId", List(ValidationError("error.path.missing"))),
        (__ \ "members", List(ValidationError("error.path.missing"))),
        (__ \ "accounts", List(ValidationError("error.path.missing"))),
        (__ \ "transactions", List(ValidationError("error.path.missing"))),
        (__ \ "created", List(ValidationError("error.path.missing")))
      ))
    )
  }

  describe("A Zone") {
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    describe("without metadata") {
      implicit val zone = Zone(
        Some("Dave's zone"),
        AccountId(0),
        Map(
          MemberId(0) ->
            Member(MemberId(0), Some("Banker"), PublicKey(publicKeyBytes)),
          MemberId(1) ->
            Member(MemberId(1), Some("Dave"), PublicKey(publicKeyBytes))
        ),
        Map(
          AccountId(0) ->
            Account(AccountId(0), Some("Bank"), Set(MemberId(0))),
          AccountId(1) ->
            Account(AccountId(1), Some("Dave's account"), Set(MemberId(1)))
        ),
        Map(
          TransactionId(0) ->
            Transaction(
              TransactionId(0),
              Some("Dave's lottery win"),
              AccountId(0),
              AccountId(1),
              BigDecimal(1000000),
              MemberId(0),
              1433611420487L
            )
        ),
        1433611420487L
      )
      implicit val zoneJson = Json.parse( s"""{"name":"Dave's zone","equityAccountId":0,"members":[{"id":0,"name":"Banker","ownerPublicKey":"${BaseEncoding.base64.encode(publicKeyBytes)}"},{"id":1,"name":"Dave","ownerPublicKey":"${BaseEncoding.base64.encode(publicKeyBytes)}"}],"accounts":[{"id":0,"name":"Bank","ownerMemberIds":[0]},{"id":1,"name":"Dave's account","ownerMemberIds":[1]}],"transactions":[{"id":0,"description":"Dave's lottery win","from":0,"to":1,"value":1000000,"creator":0,"created":1433611420487}],"created":1433611420487}""")
      it should behave like read
      it should behave like write
    }
    describe("with metadata") {
      implicit val zone = Zone(
        Some("Dave's zone"),
        AccountId(0),
        Map(
          MemberId(0) ->
            Member(MemberId(0), Some("Banker"), PublicKey(publicKeyBytes)),
          MemberId(1) ->
            Member(MemberId(1), Some("Dave"), PublicKey(publicKeyBytes))
        ),
        Map(
          AccountId(0) ->
            Account(AccountId(0), Some("Bank"), Set(MemberId(0))),
          AccountId(1) ->
            Account(AccountId(1), Some("Dave's account"), Set(MemberId(1)))
        ),
        Map(
          TransactionId(0) ->
            Transaction(
              TransactionId(0),
              Some("Dave's lottery win"),
              AccountId(0),
              AccountId(1),
              BigDecimal(1000000),
              MemberId(0),
              1433611420487L
            )
        ),
        1433611420487L,
        Some(
          Json.obj(
            "currency" -> "GBP"
          )
        )
      )
      implicit val zoneJson = Json.parse( s"""{"name":"Dave's zone","equityAccountId":0,"members":[{"id":0,"name":"Banker","ownerPublicKey":"${BaseEncoding.base64.encode(publicKeyBytes)}"},{"id":1,"name":"Dave","ownerPublicKey":"${BaseEncoding.base64.encode(publicKeyBytes)}"}],"accounts":[{"id":0,"name":"Bank","ownerMemberIds":[0]},{"id":1,"name":"Dave's account","ownerMemberIds":[1]}],"transactions":[{"id":0,"description":"Dave's lottery win","from":0,"to":1,"value":1000000,"creator":0,"created":1433611420487}],"created":1433611420487,"metadata":{"currency":"GBP"}}""")
      it should behave like read
      it should behave like write
    }
  }

}