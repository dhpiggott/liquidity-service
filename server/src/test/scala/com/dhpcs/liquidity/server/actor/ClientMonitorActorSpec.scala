package com.dhpcs.liquidity.server.actor

import akka.testkit.TestProbe
import akka.typed.scaladsl.adapter._
import com.dhpcs.liquidity.actor.protocol.clientmonitor._
import com.dhpcs.liquidity.server.InmemoryPersistenceTestFixtures
import org.scalatest.FreeSpec

class ClientMonitorActorSpec extends FreeSpec with InmemoryPersistenceTestFixtures {

  "A ClientMonitorActor" - {
    "will provide a summary of the active clients" in {
      val clientMonitor = system.spawn(ClientMonitorActor.behavior, "client-monitor")
      val testProbe     = TestProbe()
      testProbe.send(
        clientMonitor.toUntyped,
        GetActiveClientSummaries(testProbe.ref)
      )
      testProbe.expectMsg(Set.empty)
    }
  }
}
