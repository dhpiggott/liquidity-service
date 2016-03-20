package actors

import actors.ZonesMonitor._
import akka.actor.ActorSystem
import akka.testkit.{DefaultTimeout, TestKit, TestProbe}
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}

import scala.concurrent.duration._

class ZonesMonitorSpec(system: ActorSystem) extends TestKit(system)
  with DefaultTimeout with WordSpecLike with BeforeAndAfterAll {

  private val zonesMonitor = system.actorOf(ZonesMonitor.props, "zones-monitor")

  private implicit val sys = system

  def this() = this(ActorSystem("application"))

  override def afterAll() = shutdown()

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
