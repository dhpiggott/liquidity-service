package com.dhpcs.liquidity.server.actor

import akka.testkit.TestProbe
import akka.typed.scaladsl.adapter._
import com.dhpcs.liquidity.actor.protocol.clientmonitor._
import com.dhpcs.liquidity.server.InmemoryPersistenceTestFixtures
import org.scalatest.FreeSpec

class ClientMonitorActorSpec extends FreeSpec with InmemoryPersistenceTestFixtures {

  "A ClientMonitorActor" - {
    "will provide a summary of the active clients" in {
      val clientMonitorActor = system.spawn(ClientMonitorActor.behavior, "client-monitor")
      val testProbe          = TestProbe()
      testProbe.send(
        clientMonitorActor.toUntyped,
        GetActiveClientSummaries(testProbe.ref)
      )
      testProbe.expectMsg(Set.empty)
    }
  }
}
