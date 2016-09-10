package actors

import actors.ZonesMonitor.{GetZoneCount, ZoneCount}
import akka.testkit.TestProbe
import org.scalatest.WordSpec

import scala.concurrent.duration._

class ZonesMonitorSpec extends WordSpec with ZoneValidatorShardRegionProvider {

  "A ZonesMonitor" must {
    "report on the number of zones" in {
      val testProbe = TestProbe()
      val zonesMonitor = system.actorOf(ZonesMonitor.props(readJournal), "zones-monitor")
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
