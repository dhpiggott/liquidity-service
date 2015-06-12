package com.dhpcs.liquidity.models

import java.security.KeyPairGenerator

import com.google.common.io.BaseEncoding
import org.scalatest._
import play.api.data.validation.ValidationError
import play.api.libs.json._

class MemberSpec extends FunSpec with Matchers {

  def decodeError(badMemberJson: JsValue, jsError: JsError) =
    it(s"$badMemberJson should fail to decode with error $jsError") {
      Json.fromJson[Member](badMemberJson) should be(jsError)
    }

  def decode(implicit memberJson: JsValue, member: Member) =
    it(s"$memberJson should decode to $member") {
      memberJson.as[Member] should be(member)
    }

  def encode(implicit member: Member, memberJson: JsValue) =
    it(s"$member should encode to $memberJson") {
      Json.toJson(member) should be(memberJson)
    }

  describe("A JsValue of the wrong type") {
    it should behave like decodeError(
      Json.parse("0"),
      JsError(List(
        (__ \ "publicKey", List(ValidationError("error.path.missing"))),
        (__ \ "name", List(ValidationError("error.path.missing")))
      ))
    )
  }

  describe("A Member") {
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    implicit val member = Member("Dave", PublicKey(publicKeyBytes))
    implicit val memberJson = Json.parse( s"""{"name":"Dave","publicKey":"${BaseEncoding.base64.encode(publicKeyBytes)}"}""")
    it should behave like decode
    it should behave like encode
  }

}