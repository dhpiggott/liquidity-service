package com.dhpcs.liquidity.server.actor

import akka.testkit.TestProbe
import akka.typed.scaladsl.adapter._
import com.dhpcs.liquidity.actor.protocol.zonemonitor._
import com.dhpcs.liquidity.server.InmemoryPersistenceTestFixtures
import org.scalatest.FreeSpec

class ZoneMonitorActorSpec extends FreeSpec with InmemoryPersistenceTestFixtures {

  "A ZoneMonitorActor" - {
    "will provide a summary of the active zones" in {
      val zoneMonitorActor = system.spawn(ZoneMonitorActor.behavior, "zone-monitor")
      val testProbe        = TestProbe()
      testProbe.send(
        zoneMonitorActor.toUntyped,
        GetActiveZoneSummaries(testProbe.ref)
      )
      testProbe.expectMsg(Set.empty)
    }
  }
}
