package com.dhpcs.liquidity.server.actors

import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import org.iq80.leveldb.util.FileUtils
import org.scalatest.{BeforeAndAfterAll, Suite}

trait ClusteredAndPersistentActorSystem extends BeforeAndAfterAll {
  this: Suite =>

  private[this] val akkaRemotingPort = {
    val serverSocket = ServerSocketChannel.open().socket()
    serverSocket.bind(new InetSocketAddress("localhost", 0))
    val port = serverSocket.getLocalPort
    serverSocket.close()
    port
  }

  private[this] val journalDirectory = FileUtils.createTempDir("liquidity-leveldb-journal")
  private[this] val snapshotStoreDirectory = FileUtils.createTempDir("liquidity-leveldb-snapshot-store")

  private[this] val config =
    ConfigFactory.parseString(
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
         |    metrics.enabled = off
         |    seed-nodes = ["akka.tcp://liquidity@localhost:$akkaRemotingPort"]
         |    sharding.state-store-mode = ddata
         |  }
         |  extensions += "akka.cluster.ddata.DistributedData"
         |  persistence {
         |    journal {
         |      plugin = "akka.persistence.journal.leveldb"
         |      leveldb {
         |        dir = "$journalDirectory"
         |        native = off
         |      }
         |    }
         |    snapshot-store {
         |      plugin = "akka.persistence.snapshot-store.local"
         |      local.dir = "$snapshotStoreDirectory"
         |    }
         |  }
         |}
    """.stripMargin
    ).resolve()

  protected[this] implicit val system = ActorSystem("liquidity", config)

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    FileUtils.deleteRecursively(snapshotStoreDirectory)
    FileUtils.deleteRecursively(journalDirectory)
    super.afterAll()
  }
}
