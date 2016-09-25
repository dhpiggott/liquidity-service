package com.dhpcs.liquidity.server.actors

import akka.testkit.TestProbe
import com.dhpcs.liquidity.server.actors.ClientsMonitorActor.{ActiveClientsSummary, GetActiveClientsSummary}
import org.scalatest.WordSpec

import scala.concurrent.duration._

class ClientsMonitorActorSpec extends WordSpec with ZoneValidatorShardRegionProvider {

  "A ClientsMonitorActor" must {
    "report on the active clients" in {
      val testProbe = TestProbe()
      val clientsMonitor = system.actorOf(ClientsMonitorActor.props, "clients-monitor")
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
