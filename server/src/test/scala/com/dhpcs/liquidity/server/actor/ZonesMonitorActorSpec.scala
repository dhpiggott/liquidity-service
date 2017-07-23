package com.dhpcs.liquidity.server.actor

import akka.testkit.TestProbe
import com.dhpcs.liquidity.server.InmemoryPersistenceTestFixtures
import com.dhpcs.liquidity.server.actor.ZonesMonitorActor.{ActiveZonesSummary, GetActiveZonesSummary}
import org.scalatest.FreeSpec

class ZonesMonitorActorSpec extends FreeSpec with InmemoryPersistenceTestFixtures {

  "A ZonesMonitorActor" - {
    "will provide a summary of the active zones" in {
      val zonesMonitorActor = system.actorOf(ZonesMonitorActor.props, "zones-monitor")
      val testProbe         = TestProbe()
      testProbe.send(
        zonesMonitorActor,
        GetActiveZonesSummary
      )
      testProbe.expectMsg(ActiveZonesSummary(Set.empty))
    }
  }
}
