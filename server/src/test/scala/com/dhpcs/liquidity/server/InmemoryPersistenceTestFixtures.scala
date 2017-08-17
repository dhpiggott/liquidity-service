package com.dhpcs.liquidity.server

import akka.actor.ActorSystem
import com.dhpcs.liquidity
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Suite}

trait InmemoryPersistenceTestFixtures extends BeforeAndAfterAll { this: Suite =>

  private[this] val akkaRemotingPort = liquidity.testkit.TestKit.freePort()
  private[this] val config           = ConfigFactory.parseString(s"""
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
       |    roles = ["zone-host", "client-relay"]
       |    seed-nodes = ["akka.tcp://liquidity@localhost:$akkaRemotingPort"]
       |    jmx.multi-mbeans-in-same-jvm = on
       |  }
       |  extensions += "akka.persistence.Persistence"
       |  persistence {
       |    journal {
       |      auto-start-journals = ["inmemory-journal"]
       |      plugin = "inmemory-journal"
       |    }
       |    snapshot-store {
       |      auto-start-journals = ["inmemory-snapshot-store"]
       |      plugin = "inmemory-snapshot-store"
       |    }
       |  }
       |}
     """.stripMargin).resolve()

  protected[this] implicit val system: ActorSystem = ActorSystem("liquidity", config)

  override protected def afterAll(): Unit = {
    akka.testkit.TestKit.shutdownActorSystem(system)
    super.afterAll()
  }
}
