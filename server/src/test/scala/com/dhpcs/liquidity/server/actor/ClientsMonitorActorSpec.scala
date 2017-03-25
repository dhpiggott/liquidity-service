package com.dhpcs.liquidity.server.actor

import akka.actor.Deploy
import akka.testkit.TestProbe
import com.dhpcs.liquidity.server.InMemPersistenceTestFixtures
import com.dhpcs.liquidity.server.actor.ClientsMonitorActor.{ActiveClientsSummary, GetActiveClientsSummary}
import org.scalatest.FreeSpec

class ClientsMonitorActorSpec extends FreeSpec with InMemPersistenceTestFixtures {

  "A ClientsMonitorActor" - {
    "will provide a summary of the active clients" in {
      val testProbe      = TestProbe()
      val clientsMonitor = system.actorOf(ClientsMonitorActor.props.withDeploy(Deploy.local), "clients-monitor")
      try {
        testProbe.send(
          clientsMonitor,
          GetActiveClientsSummary
        )
        testProbe.expectMsg(ActiveClientsSummary(Seq.empty))
      } finally system.stop(clientsMonitor)
    }
  }
}
