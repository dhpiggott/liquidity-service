package com.dhpcs.liquidity.server

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Suite}

trait InMemPersistenceTestFixtures extends BeforeAndAfterAll { this: Suite =>

  private[this] val akkaRemotingPort = freePort()

  private[this] val config = ConfigFactory.parseString(s"""
       |akka {
       |  loglevel = "ERROR"
       |  actor {
       |    provider = "akka.cluster.ClusterActorRefProvider"
       |    serializers {
       |      client-connection-protocol = "com.dhpcs.liquidity.actor.protocol.ClientConnectionMessageSerializer"
       |      zone-validator-protocol = "com.dhpcs.liquidity.actor.protocol.ZoneValidatorMessageSerializer"
       |    }
       |    serialization-bindings {
       |      "com.trueaccord.scalapb.GeneratedMessage" = proto
       |      "com.dhpcs.liquidity.actor.protocol.ClientConnectionMessage" = client-connection-protocol
       |      "com.dhpcs.liquidity.actor.protocol.ZoneValidatorMessage" = zone-validator-protocol
       |    }
       |    enable-additional-serialization-bindings = on
       |    allow-java-serialization = on
       |    serialize-messages = on
       |    serialize-creators = on
       |  }
       |  remote.netty.tcp {
       |    hostname = "localhost"
       |    port = $akkaRemotingPort
       |  }
       |  cluster {
       |    metrics.enabled = off
       |    roles = ["zone-host", "client-relay"]
       |    seed-nodes = ["akka.tcp://liquidity@localhost:$akkaRemotingPort"]
       |    sharding.state-store-mode = ddata
       |  }
       |  extensions += "akka.cluster.ddata.DistributedData"
       |  extensions += "akka.persistence.Persistence"
       |  persistence.journal {
       |    auto-start-journals = ["akka.persistence.journal.inmem"]
       |    plugin = "akka.persistence.journal.inmem"
       |    inmem {
       |      event-adapters {
       |        zone-event-write-adapter = "com.dhpcs.liquidity.persistence.ZoneWriteEventAdapter"
       |        zone-event-read-adapter = "com.dhpcs.liquidity.persistence.ZoneReadEventAdapter"
       |      }
       |      event-adapter-bindings {
       |        "com.dhpcs.liquidity.persistence.ZoneEvent" = [zone-event-write-adapter]
       |        "com.dhpcs.liquidity.proto.persistence.ZoneEvent" = [zone-event-read-adapter]
       |      }
       |    }
       |  }
       |}
     """.stripMargin).resolve()

  protected[this] implicit val system = ActorSystem("liquidity", config)

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }
}
