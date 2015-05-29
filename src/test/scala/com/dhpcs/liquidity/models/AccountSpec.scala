package com.dhpcs.liquidity.models

import org.scalatest._
import play.api.libs.json.Json

class AccountSpec extends FlatSpec with Matchers {

  "Account formatting" should "be reversible" in {
    val account = Account("Dave's account", Set(MemberId.generate))
    note(s"account: $account")
    val accountJson = Json.toJson(account)
    note(s"accountJson: $accountJson")
    accountJson.as[Account] should be(account)
  }

}