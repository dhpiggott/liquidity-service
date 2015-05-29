package com.dhpcs.liquidity.models

import java.util.UUID

import org.scalatest._
import play.api.libs.json.Json

case class TestId(id: UUID) extends Identifier

object TestId extends IdentifierCompanion[TestId]

class IdentifierSpec extends FlatSpec with Matchers {

  "Identifier formatting" should "be reversible" in {
    val testId = TestId.generate
    note(s"testId: $testId")
    val testIdJson = Json.toJson(testId)
    note(s"testIdJson: $testIdJson")
    testIdJson.as[TestId] should be(testId)
  }

}