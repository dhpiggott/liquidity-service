package com.dhpcs.liquidity.server.actor

import akka.testkit.TestProbe
import akka.typed.scaladsl.adapter._
import com.dhpcs.liquidity.actor.protocol.clientmonitor._
import com.dhpcs.liquidity.model.PublicKey
import com.dhpcs.liquidity.server.{InmemoryPersistenceTestFixtures, TestKit}
import org.scalatest.FreeSpec

class ClientMonitorActorSpec
    extends FreeSpec
    with InmemoryPersistenceTestFixtures {

  "A ClientMonitorActor" - {
    "provides a summary of the active clients" in {
      val clientMonitor =
        system.spawn(ClientMonitorActor.behavior, "clientMonitor")
      val testProbe = TestProbe()
      val activeClientSummary =
        ActiveClientSummary(PublicKey(TestKit.rsaPublicKey.getEncoded))
      testProbe.send(
        clientMonitor.toUntyped,
        UpsertActiveClientSummary(testProbe.ref, activeClientSummary)
      )
      testProbe.send(
        clientMonitor.toUntyped,
        GetActiveClientSummaries(testProbe.ref)
      )
      testProbe.expectMsg(Set(activeClientSummary))
    }
  }
}
