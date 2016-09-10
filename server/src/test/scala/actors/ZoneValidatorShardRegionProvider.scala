package actors

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
         |  actor.provider = "akka.cluster.ClusterActorRefProvider"
         |  remote.netty.tcp {
         |    hostname = "localhost"
         |    port = $akkaRemotingPort
         |  }
         |  cluster.seed-nodes = ["akka.tcp://liquidity@localhost:$akkaRemotingPort"]
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
    typeName = ZoneValidator.ShardName,
    entityProps = ZoneValidator.props,
    settings = ClusterShardingSettings(system),
    extractEntityId = ZoneValidator.extractEntityId,
    extractShardId = ZoneValidator.extractShardId
  )

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    Try(FileUtils.deleteRecursively(levelDbDirectory)).failed.foreach(_.printStackTrace)
    super.afterAll()
  }
}
