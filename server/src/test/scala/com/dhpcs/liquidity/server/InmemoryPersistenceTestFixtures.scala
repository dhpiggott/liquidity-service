package com.dhpcs.liquidity.server

import akka.actor.ActorSystem
import com.dhpcs.liquidity.testkit.TestKit
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Suite}

trait InmemoryPersistenceTestFixtures extends BeforeAndAfterAll { this: Suite =>

  private[this] val akkaRemotingPort = TestKit.freePort()
  private[this] val config = ConfigFactory.parseString(s"""
       |akka {
       |  loglevel = "WARNING"
       |  actor {
       |    provider = "akka.cluster.ClusterActorRefProvider"
       |    serializers {
       |      zone-record = "com.dhpcs.liquidity.server.serialization.ZoneRecordSerializer"
       |    }
       |    serialization-bindings {
       |      "com.dhpcs.liquidity.persistence.zone.ZoneRecord" = zone-record
       |    }
       |    allow-java-serialization = off
       |  }
       |  remote.netty.tcp {
       |    hostname = "localhost"
       |    port = $akkaRemotingPort
       |  }
       |  cluster {
       |    metrics.enabled = off
       |    seed-nodes = ["akka.tcp://liquidity@localhost:$akkaRemotingPort"]
       |    jmx.multi-mbeans-in-same-jvm = on
       |  }
       |  persistence {
       |    journal.plugin = "inmemory-journal"
       |    snapshot-store.plugin = "inmemory-snapshot-store"
       |  }
       |}
       |inmemory-journal {
       |  event-adapters {
       |    zone-event = "com.dhpcs.liquidity.server.ZoneEventAdapter"
       |  }
       |  event-adapter-bindings {
       |    "com.dhpcs.liquidity.persistence.zone.ZoneEventEnvelope" = zone-event
       |  }
       |}
     """.stripMargin)

  protected[this] implicit val system: ActorSystem =
    ActorSystem("liquidity", config)

  override protected def afterAll(): Unit = {
    akka.testkit.TestKit.shutdownActorSystem(system)
    super.afterAll()
  }
}
