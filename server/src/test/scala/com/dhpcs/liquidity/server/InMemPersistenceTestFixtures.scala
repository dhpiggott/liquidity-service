package com.dhpcs.liquidity.server

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Suite}

trait InMemPersistenceTestFixtures extends BeforeAndAfterAll { this: Suite =>

  private[this] val akkaRemotingPort = freePort()

  private[this] val config = ConfigFactory.parseString(s"""
       |akka {
       |  loglevel = "WARNING"
       |  actor {
       |    provider = "akka.cluster.ClusterActorRefProvider"
       |    serializers {
       |      zone-validator-record = "com.dhpcs.liquidity.server.serialization.ZoneValidatorRecordSerializer"
       |      zone-validator-message = "com.dhpcs.liquidity.server.serialization.ZoneValidatorMessageSerializer"
       |      client-connection-message = "com.dhpcs.liquidity.server.serialization.ClientConnectionMessageSerializer"
       |    }
       |    serialization-bindings {
       |      "com.dhpcs.liquidity.persistence.ZoneValidatorRecord" = zone-validator-record
       |      "com.dhpcs.liquidity.actor.protocol.ZoneValidatorMessage" = zone-validator-message
       |      "com.dhpcs.liquidity.actor.protocol.ClientConnectionMessage" = client-connection-message
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
       |  persistence.journal {
       |    auto-start-journals = ["akka.persistence.journal.inmem"]
       |    plugin = "akka.persistence.journal.inmem"
       |  }
       |}
     """.stripMargin).resolve()

  protected[this] implicit val system: ActorSystem = ActorSystem("liquidity", config)

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }
}
