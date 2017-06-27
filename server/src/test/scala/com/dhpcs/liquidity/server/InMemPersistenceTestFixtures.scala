package com.dhpcs.liquidity.server

import java.net.InetAddress

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
       |      zone-event = "com.dhpcs.liquidity.server.serialization.ZoneEventSerializer"
       |      zone-validator-protocol = "com.dhpcs.liquidity.server.serialization.ZoneValidatorMessageSerializer"
       |      client-connection-protocol = "com.dhpcs.liquidity.server.serialization.ClientConnectionMessageSerializer"
       |    }
       |    serialization-bindings {
       |      "com.dhpcs.liquidity.persistence.ZoneEvent" = zone-event
       |      "com.dhpcs.liquidity.actor.protocol.ZoneValidatorMessage" = zone-validator-protocol
       |      "com.dhpcs.liquidity.actor.protocol.ClientConnectionMessage" = client-connection-protocol
       |      "com.dhpcs.liquidity.proto.ws.protocol.ServerMessage" = proto
       |      "com.dhpcs.liquidity.proto.ws.protocol.ClientMessage" = proto
       |    }
       |    allow-java-serialization = off
       |  }
       |  remote.netty.tcp.port = $akkaRemotingPort
       |  cluster {
       |    metrics.enabled = off
       |    roles = ["zone-host", "client-relay"]
       |    seed-nodes = ["akka.tcp://liquidity@${InetAddress.getLocalHost.getHostAddress}:$akkaRemotingPort"]
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
