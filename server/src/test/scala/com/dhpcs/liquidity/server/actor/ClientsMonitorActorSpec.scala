package com.dhpcs.liquidity.server.actor

import akka.testkit.TestProbe
import com.dhpcs.liquidity.server.InmemoryPersistenceTestFixtures
import com.dhpcs.liquidity.server.actor.ClientsMonitorActor.{ActiveClientsSummary, GetActiveClientsSummary}
import org.scalatest.FreeSpec

import scala.collection.immutable.Seq

class ClientsMonitorActorSpec extends FreeSpec with InmemoryPersistenceTestFixtures {

  "A ClientsMonitorActor" - {
    "will provide a summary of the active clients" in {
      val clientsMonitorActor = system.actorOf(ClientsMonitorActor.props, "clients-monitor")
      val testProbe           = TestProbe()
      testProbe.send(
        clientsMonitorActor,
        GetActiveClientsSummary
      )
      testProbe.expectMsg(ActiveClientsSummary(Seq.empty))
    }
  }
}
