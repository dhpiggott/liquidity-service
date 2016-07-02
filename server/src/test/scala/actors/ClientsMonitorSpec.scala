package actors

import actors.ClientsMonitor.{ActiveClientsSummary, GetActiveClientsSummary}
import akka.testkit.TestProbe
import org.scalatest.WordSpec

import scala.concurrent.duration._

class ClientsMonitorSpec extends WordSpec with ZoneValidatorShardRegionProvider {

  "A ClientsMonitor" must {
    "report on the active clients" in {
      val testProbe = TestProbe()
      val clientsMonitor = system.actorOf(ClientsMonitor.props, "clients-monitor")
      testProbe.send(
        clientsMonitor,
        GetActiveClientsSummary
      )
      testProbe.expectMsgPF(5.seconds) {
        case _: ActiveClientsSummary =>
      }
    }
  }
}
