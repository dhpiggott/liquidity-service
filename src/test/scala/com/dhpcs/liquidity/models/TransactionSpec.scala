package com.dhpcs.liquidity.models

import java.util.UUID

import com.dhpcs.json.FormatBehaviors
import org.scalatest._
import play.api.data.validation.ValidationError
import play.api.libs.json._

class TransactionSpec extends FunSpec with FormatBehaviors[Transaction] with Matchers {

  describe("A JsValue of the wrong type") {
    it should behave like readError(
      Json.parse( """0"""),
      JsError(List(
        (__ \ "description", List(ValidationError("error.path.missing"))),
        (__ \ "from", List(ValidationError("error.path.missing"))),
        (__ \ "to", List(ValidationError("error.path.missing"))),
        (__ \ "value", List(ValidationError("error.path.missing"))),
        (__ \ "creator", List(ValidationError("error.path.missing"))),
        (__ \ "created", List(ValidationError("error.path.missing")))
      ))
    )
  }

  describe("A Transaction") {
    describe("without metadata") {
      implicit val transaction = Transaction(
        "test",
        AccountId(UUID.fromString("28c331cd-35eb-45b2-a478-82334d7a4593")),
        AccountId(UUID.fromString("a1191a07-fc84-4245-975a-9798a9c26a9e")),
        BigDecimal(1000000),
        MemberId(UUID.fromString("7fe65834-7353-4fb3-b8b3-fe4c1f3aa2c0")),
        1434115187612L
      )
      implicit val transactionJson = Json.parse( """{"description":"test","from":"28c331cd-35eb-45b2-a478-82334d7a4593","to":"a1191a07-fc84-4245-975a-9798a9c26a9e","value":1000000,"creator":"7fe65834-7353-4fb3-b8b3-fe4c1f3aa2c0","created":1434115187612}""")
      it should behave like read
      it should behave like write
    }
    describe("with metadata") {
      implicit val transaction = Transaction(
        "test",
        AccountId(UUID.fromString("28c331cd-35eb-45b2-a478-82334d7a4593")),
        AccountId(UUID.fromString("a1191a07-fc84-4245-975a-9798a9c26a9e")),
        BigDecimal(1000000),
        MemberId(UUID.fromString("7fe65834-7353-4fb3-b8b3-fe4c1f3aa2c0")),
        1434115187612L,
        Some(
          JsObject(
            Seq("property" -> JsString("Mayfair"))
          )
        )
      )
      implicit val transactionJson = Json.parse( """{"description":"test","from":"28c331cd-35eb-45b2-a478-82334d7a4593","to":"a1191a07-fc84-4245-975a-9798a9c26a9e","value":1000000,"creator":"7fe65834-7353-4fb3-b8b3-fe4c1f3aa2c0","created":1434115187612,"metadata":{"property":"Mayfair"}}""")
      it should behave like read
      it should behave like write
    }
  }

}