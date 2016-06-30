package com.dhpcs.liquidity.protocol

import java.security.KeyPairGenerator

import com.dhpcs.json.FormatBehaviors
import okio.ByteString
import org.scalatest.{FunSpec, Matchers}
import play.api.data.validation.ValidationError
import play.api.libs.json.{JsError, Json, __}

class MemberSpec extends FunSpec with FormatBehaviors[Member] with Matchers {
  describe("A JsValue of the wrong type")(
    it should behave like readError(
      Json.parse(
        """
          |0""".stripMargin
      ),
      JsError(List(
        (__ \ "id", List(ValidationError("error.path.missing"))),
        (__ \ "ownerPublicKey", List(ValidationError("error.path.missing")))
      ))
    )
  )

  describe("A Member") {
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    describe("without a name or metadata") {
      implicit val member = Member(MemberId(0), PublicKey(publicKeyBytes))
      implicit val memberJson = Json.parse(
        s"""
           |{
           |  "id":0,
           |  "ownerPublicKey":"${ByteString.of(publicKeyBytes: _*).base64}"
           |}""".stripMargin
      )
      it should behave like read
      it should behave like write
    }
    describe("with a name and metadata") {
      implicit val member = Member(
        MemberId(0),
        PublicKey(publicKeyBytes),
        Some("Dave"),
        Some(
          Json.obj(
            "color" -> "0x0000FF"
          )
        )
      )
      implicit val memberJson = Json.parse(
        s"""
           |{
           |  "id":0,
           |  "ownerPublicKey":"${ByteString.of(publicKeyBytes: _*).base64}",
           |  "name":"Dave",
           |  "metadata":{"color":"0x0000FF"}
           |}""".stripMargin
      )
      it should behave like read
      it should behave like write
    }
  }
}
