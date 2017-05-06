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
       |      zone-event = "com.dhpcs.liquidity.persistence.ZoneEventSerializer"
       |      client-connection-protocol = "com.dhpcs.liquidity.actor.protocol.ClientConnectionMessageSerializer"
       |      zone-validator-protocol = "com.dhpcs.liquidity.actor.protocol.ZoneValidatorMessageSerializer"
       |    }
       |    serialization-bindings {
       |      "com.dhpcs.liquidity.persistence.ZoneEvent" = zone-event
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
       |  }
       |}
     """.stripMargin).resolve()

  protected[this] implicit val system = ActorSystem("liquidity", config)

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }
}
