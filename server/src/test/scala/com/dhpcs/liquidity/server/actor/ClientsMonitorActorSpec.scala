package com.dhpcs.liquidity.server.actor

import akka.testkit.TestProbe
import akka.typed.scaladsl.adapter._
import com.dhpcs.liquidity.actor.protocol.clientmonitor._
import com.dhpcs.liquidity.server.InmemoryPersistenceTestFixtures
import org.scalatest.FreeSpec

class ClientsMonitorActorSpec extends FreeSpec with InmemoryPersistenceTestFixtures {

  "A ClientsMonitorActor" - {
    "will provide a summary of the active clients" in {
      val clientsMonitorActor = system.spawn(ClientsMonitorActor.behaviour, "clients-monitor")
      val testProbe           = TestProbe()
      testProbe.send(
        clientsMonitorActor.toUntyped,
        GetActiveClientSummaries(testProbe.ref)
      )
      testProbe.expectMsg(Set.empty)
    }
  }
}
