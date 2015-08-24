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
            Member(Some("Banker"), PublicKey(publicKeyBytes)),
          MemberId(1) ->
            Member(Some("Dave"), PublicKey(publicKeyBytes))
        ),
        Map(
          AccountId(0) ->
            Account(Some("Bank"), Set(MemberId(0))),
          AccountId(1) ->
            Account(Some("Dave's account"), Set(MemberId(1)))
        ),
        Map(
          TransactionId(0) ->
            Transaction(
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
      implicit val zoneJson = Json.parse( s"""{"name":"Dave's zone","equityAccountId":0,"members":{"0":{"name":"Banker","publicKey":"${BaseEncoding.base64.encode(publicKeyBytes)}"},"1":{"name":"Dave","publicKey":"${BaseEncoding.base64.encode(publicKeyBytes)}"}},"accounts":{"0":{"name":"Bank","owners":[0]},"1":{"name":"Dave's account","owners":[1]}},"transactions":{"0":{"description":"Dave's lottery win","from":0,"to":1,"value":1000000,"creator":0,"created":1433611420487}},"created":1433611420487}""")
      it should behave like read
      it should behave like write
    }
    describe("with metadata") {
      implicit val zone = Zone(
        Some("Dave's zone"),
        AccountId(0),
        Map(
          MemberId(0) ->
            Member(Some("Banker"), PublicKey(publicKeyBytes)),
          MemberId(1) ->
            Member(Some("Dave"), PublicKey(publicKeyBytes))
        ),
        Map(
          AccountId(0) ->
            Account(Some("Bank"), Set(MemberId(0))),
          AccountId(1) ->
            Account(Some("Dave's account"), Set(MemberId(1)))
        ),
        Map(
          TransactionId(0) ->
            Transaction(
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
      implicit val zoneJson = Json.parse( s"""{"name":"Dave's zone","equityAccountId":0,"members":{"0":{"name":"Banker","publicKey":"${BaseEncoding.base64.encode(publicKeyBytes)}"},"1":{"name":"Dave","publicKey":"${BaseEncoding.base64.encode(publicKeyBytes)}"}},"accounts":{"0":{"name":"Bank","owners":[0]},"1":{"name":"Dave's account","owners":[1]}},"transactions":{"0":{"description":"Dave's lottery win","from":0,"to":1,"value":1000000,"creator":0,"created":1433611420487}},"created":1433611420487,"metadata":{"currency":"GBP"}}""")
      it should behave like read
      it should behave like write
    }
  }

}