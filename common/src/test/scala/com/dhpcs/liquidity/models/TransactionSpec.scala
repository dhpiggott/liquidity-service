package com.dhpcs.liquidity.models

import com.dhpcs.json.FormatBehaviors
import org.scalatest._
import play.api.data.validation.ValidationError
import play.api.libs.json._

class TransactionSpec extends FunSpec with FormatBehaviors[Transaction] with Matchers {

  describe("A JsValue of the wrong type") {
    it should behave like readError(
      Json.parse( """0"""),
      JsError(List(
        (__ \ "id", List(ValidationError("error.path.missing"))),
        (__ \ "from", List(ValidationError("error.path.missing"))),
        (__ \ "to", List(ValidationError("error.path.missing"))),
        (__ \ "value", List(ValidationError("error.path.missing"))),
        (__ \ "creator", List(ValidationError("error.path.missing"))),
        (__ \ "created", List(ValidationError("error.path.missing")))
      ))
    )
  }

  describe("A Transaction") {
    describe("without a description or metadata") {
      implicit val transaction = Transaction(
        TransactionId(0),
        AccountId(0),
        AccountId(1),
        BigDecimal(1000000),
        MemberId(2),
        1434115187612L
      )
      implicit val transactionJson = Json.parse( """{"id":0,"from":0,"to":1,"value":1000000,"creator":2,"created":1434115187612}""")
      it should behave like read
      it should behave like write
    }
    describe("with a description and metadata") {
      implicit val transaction = Transaction(
        TransactionId(0),
        AccountId(0),
        AccountId(1),
        BigDecimal(1000000),
        MemberId(2),
        1434115187612L,
        Some("Property purchase"),
        Some(
          Json.obj(
            "property" -> "The TARDIS"
          )
        )
      )
      implicit val transactionJson = Json.parse( """{"id":0,"from":0,"to":1,"value":1000000,"creator":2,"created":1434115187612,"description":"Property purchase","metadata":{"property":"The TARDIS"}}""")
      it should behave like read
      it should behave like write
    }
  }

}
