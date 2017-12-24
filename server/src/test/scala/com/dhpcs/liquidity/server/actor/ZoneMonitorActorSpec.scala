package com.dhpcs.liquidity.server.actor

import java.util.UUID

import akka.testkit.TestProbe
import akka.typed.scaladsl.adapter._
import com.dhpcs.liquidity.actor.protocol.zonemonitor._
import com.dhpcs.liquidity.model.ZoneId
import com.dhpcs.liquidity.server.InmemoryPersistenceTestFixtures
import org.scalatest.FreeSpec

class ZoneMonitorActorSpec
    extends FreeSpec
    with InmemoryPersistenceTestFixtures {

  "A ZoneMonitorActor" - {
    "provides a summary of the active zones" in {
      val zoneMonitor = system.spawn(ZoneMonitorActor.behavior, "zoneMonitor")
      val testProbe = TestProbe()
      val activeZoneSummary = ActiveZoneSummary(
        zoneId = ZoneId(UUID.randomUUID().toString),
        members = 0,
        accounts = 0,
        transactions = 0,
        metadata = None,
        clientConnections = Set.empty
      )
      testProbe.send(
        zoneMonitor.toUntyped,
        UpsertActiveZoneSummary(testProbe.ref, activeZoneSummary)
      )
      testProbe.send(
        zoneMonitor.toUntyped,
        GetActiveZoneSummaries(testProbe.ref)
      )
      testProbe.expectMsg(Set(activeZoneSummary))
    }
  }
}
