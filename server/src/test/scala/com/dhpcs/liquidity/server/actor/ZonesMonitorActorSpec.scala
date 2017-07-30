package com.dhpcs.liquidity.server.actor

import akka.testkit.TestProbe
import akka.typed.scaladsl.adapter._
import com.dhpcs.liquidity.actor.protocol.GetActiveZoneSummaries
import com.dhpcs.liquidity.server.InmemoryPersistenceTestFixtures
import org.scalatest.FreeSpec

class ZonesMonitorActorSpec extends FreeSpec with InmemoryPersistenceTestFixtures {

  "A ZonesMonitorActor" - {
    "will provide a summary of the active zones" in {
      val zonesMonitorActor = system.spawn(ZonesMonitorActor.behaviour, "zones-monitor")
      val testProbe         = TestProbe()
      testProbe.send(
        zonesMonitorActor.toUntyped,
        GetActiveZoneSummaries(testProbe.ref)
      )
      testProbe.expectMsg(Set.empty)
    }
  }
}
