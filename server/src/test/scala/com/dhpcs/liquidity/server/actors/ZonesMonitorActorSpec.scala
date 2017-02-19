package com.dhpcs.liquidity.server.actors

import akka.testkit.TestProbe
import com.dhpcs.liquidity.server.InMemPersistenceTestFixtures
import com.dhpcs.liquidity.server.actors.ZonesMonitorActor.{GetZoneCount, ZoneCount}
import org.scalatest.WordSpec

import scala.concurrent.Future

class ZonesMonitorActorSpec extends WordSpec with InMemPersistenceTestFixtures {

  "A ZonesMonitorActor" should {
    "provide a count of the number of zones" in {
      val testProbe    = TestProbe()
      val zonesMonitor = system.actorOf(ZonesMonitorActor.props(Future.successful(0)), "zones-monitor")
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
