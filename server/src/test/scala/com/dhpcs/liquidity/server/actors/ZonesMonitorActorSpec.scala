package com.dhpcs.liquidity.server.actors

import akka.testkit.TestProbe
import com.dhpcs.liquidity.server.actors.ZonesMonitorActor.{GetZoneCount, ZoneCount}
import org.scalatest.WordSpec

import scala.concurrent.duration._

class ZonesMonitorActorSpec extends WordSpec with ZoneValidatorShardRegionProvider {

  "A ZonesMonitorActor" must {
    "report on the number of zones" in {
      val testProbe = TestProbe()
      val zonesMonitor = system.actorOf(ZonesMonitorActor.props(readJournal), "zones-monitor")
      testProbe.send(
        zonesMonitor,
        GetZoneCount
      )
      testProbe.expectMsgPF(5.seconds) {
        case _: ZoneCount =>
      }
    }
  }
}
