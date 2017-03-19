package com.dhpcs.liquidity.server.actor

import akka.actor.Deploy
import akka.testkit.TestProbe
import com.dhpcs.liquidity.server.InMemPersistenceTestFixtures
import com.dhpcs.liquidity.server.actor.ZonesMonitorActor.{GetZoneCount, ZoneCount}
import org.scalatest.FreeSpec

import scala.concurrent.Future

class ZonesMonitorActorSpec extends FreeSpec with InMemPersistenceTestFixtures {

  "A ZonesMonitorActor" - {
    "should provide a count of the number of zones" in {
      val testProbe = TestProbe()
      val zonesMonitor =
        system.actorOf(ZonesMonitorActor.props(Future.successful(0)).withDeploy(Deploy.local), "zones-monitor")
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
