package actors

import actors.ZonesMonitor.{GetZoneCount, ZoneCount}
import akka.actor.ActorSystem
import akka.testkit.{DefaultTimeout, TestKit, TestProbe}
import org.scalatest.WordSpecLike

import scala.concurrent.duration._

class ZonesMonitorSpec(implicit system: ActorSystem) extends TestKit(system)
  with DefaultTimeout with WordSpecLike {
  private[this] val zonesMonitor = system.actorOf(ZonesMonitor.props, "zones-monitor")

  "A ZonesValidatorMonitor" must {
    "report the total number of zones created" in {
      val testProbe = TestProbe()
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
