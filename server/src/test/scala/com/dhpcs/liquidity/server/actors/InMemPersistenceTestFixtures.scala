package com.dhpcs.liquidity.server.actors

import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Suite}

trait InMemPersistenceTestFixtures extends BeforeAndAfterAll { this: Suite =>

  private[this] val akkaRemotingPort = {
    val serverSocket = ServerSocketChannel.open().socket()
    serverSocket.bind(new InetSocketAddress("localhost", 0))
    val port = serverSocket.getLocalPort
    serverSocket.close()
    port
  }

  private[this] val config =
    ConfigFactory
      .parseString(
        s"""
         |akka {
         |  loglevel = "ERROR"
         |  actor {
         |    provider = "akka.cluster.ClusterActorRefProvider"
         |    serializers.event = "com.dhpcs.liquidity.persistence.PlayJsonEventSerializer"
         |    serialization-bindings {
         |      "java.io.Serializable" = none
         |      "com.dhpcs.liquidity.persistence.Event" = event
         |    }
         |  }
         |  remote.netty.tcp {
         |    hostname = "localhost"
         |    port = $akkaRemotingPort
         |  }
         |  cluster {
         |    auto-down-unreachable-after = 5s
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
    """.stripMargin
      )
      .resolve()

  protected[this] implicit val system = ActorSystem("liquidity", config)

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }
}
