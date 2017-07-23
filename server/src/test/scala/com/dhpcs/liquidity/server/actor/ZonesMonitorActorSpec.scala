package com.dhpcs.liquidity.server.actor

import akka.testkit.TestProbe
import com.dhpcs.liquidity.server.InmemoryPersistenceTestFixtures
import com.dhpcs.liquidity.server.actor.ZonesMonitorActor.{GetZoneCount, ZoneCount}
import org.scalatest.FreeSpec

import scala.concurrent.Future

class ZonesMonitorActorSpec extends FreeSpec with InmemoryPersistenceTestFixtures {

  "A ZonesMonitorActor" - {
    "will provide a count of the number of zones" in {
      val testProbe = TestProbe()
      val zonesMonitor =
        system.actorOf(ZonesMonitorActor.props(() => Future.successful(0)), "zones-monitor")
      try {
        testProbe.send(
          zonesMonitor,
          GetZoneCount
        )
        testProbe.expectMsg(ZoneCount(0))
      } finally system.stop(zonesMonitor)
    }
  }
}
