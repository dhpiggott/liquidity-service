package com.dhpcs.liquidity.server.actors

import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel

import akka.actor.ActorSystem
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import org.iq80.leveldb.util.FileUtils
import org.scalatest.{BeforeAndAfterAll, Suite}

import scala.util.Try

trait ZoneValidatorShardRegionProvider extends BeforeAndAfterAll {
  this: Suite =>
  private[this] val akkaRemotingPort = {
    val serverSocket = ServerSocketChannel.open().socket()
    serverSocket.bind(new InetSocketAddress("localhost", 0))
    val port = serverSocket.getLocalPort
    serverSocket.close()
    port
  }
  private[this] val levelDbDirectory = FileUtils.createTempDir("liquidity-leveldb")

  protected[this] def config =
    ConfigFactory.parseString(
      s"""
         |akka {
         |  loglevel = "OFF"
         |  actor {
         |    provider = "akka.cluster.ClusterActorRefProvider"
         |    serializers.event = "com.dhpcs.liquidity.model.PlayJsonEventSerializer"
         |    serialization-bindings {
         |      "java.io.Serializable" = none
         |      "com.dhpcs.liquidity.model.Event" = event
         |    }
         |  }
         |  remote.netty.tcp {
         |    hostname = "localhost"
         |    port = $akkaRemotingPort
         |  }
         |  cluster {
         |    metrics.enabled = off
         |    seed-nodes = ["akka.tcp://liquidity@localhost:$akkaRemotingPort"]
         |  }
         |  persistence.journal {
         |    plugin = "akka.persistence.journal.leveldb"
         |    leveldb {
         |      dir = "$levelDbDirectory"
         |      native = off
         |    }
         |  }
         |}
    """.stripMargin
    )

  protected[this] implicit val system = ActorSystem("liquidity", config)

  protected[this] val readJournal = PersistenceQuery(system)
    .readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)

  protected[this] val zoneValidatorShardRegion = ClusterSharding(system).start(
    typeName = ZoneValidatorActor.ShardName,
    entityProps = ZoneValidatorActor.props,
    settings = ClusterShardingSettings(system),
    extractEntityId = ZoneValidatorActor.extractEntityId,
    extractShardId = ZoneValidatorActor.extractShardId
  )

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    Try(FileUtils.deleteRecursively(levelDbDirectory)).failed.foreach(_.printStackTrace)
    super.afterAll()
  }
}
