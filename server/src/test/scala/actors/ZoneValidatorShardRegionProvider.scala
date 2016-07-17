package actors

import java.io.File
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel

import actors.ZoneValidatorShardRegionProvider._
import akka.actor.ActorSystem
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.persistence.cassandra.testkit.CassandraLauncher
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Suite}

import scala.util.Try

object ZoneValidatorShardRegionProvider {
  private def freePort(): Int = {
    val serverSocket = ServerSocketChannel.open().socket()
    serverSocket.bind(new InetSocketAddress("127.0.0.1", 0))
    val port = serverSocket.getLocalPort
    serverSocket.close()
    port
  }

  private def deleteFile(path: File): Unit = {
    if (path.isDirectory) {
      path.listFiles().foreach(deleteFile)
    }
    path.delete()
  }
}

trait ZoneValidatorShardRegionProvider extends BeforeAndAfterAll {
  this: Suite =>
  protected[this] def config =
    ConfigFactory.parseString(
      s"""
         |akka {
         |  remote.netty.tcp {
         |    hostname = "127.0.0.1"
         |    port = $akkaRemotingPort
         |  }
         |  cluster.seed-nodes = ["akka.tcp://liquidity@127.0.0.1:$akkaRemotingPort"]
         |}
         |cassandra-journal.contact-points = ["127.0.0.1:${CassandraLauncher.randomPort}"]
    """.stripMargin
    ).withFallback(ConfigFactory.defaultApplication())

  protected[this] implicit val system = ActorSystem("liquidity", config)

  // This is lazy so we don't initialise it until the persistence backend is ready -- otherwise we get a lot of log spam
  protected[this] lazy val zoneValidatorShardRegion = ClusterSharding(system).start(
    typeName = ZoneValidator.ShardName,
    entityProps = ZoneValidator.props,
    settings = ClusterShardingSettings(system),
    extractEntityId = ZoneValidator.extractEntityId,
    extractShardId = ZoneValidator.extractShardId
  )

  private[this] lazy val akkaRemotingPort = freePort()

  private[this] val cassandraDirectory = File.createTempFile("cassandra-local", null)

  cassandraDirectory.deleteOnExit()

  override def beforeAll(): Unit = {
    super.beforeAll()
    CassandraLauncher.start(
      cassandraDirectory = cassandraDirectory,
      configResource = CassandraLauncher.DefaultTestConfigResource,
      clean = true,
      port = 0
    )
    AwaitPersistenceInit.waitForPersistenceInitialisation(system)
    // Wait for this to init too before allowing tests to run
    zoneValidatorShardRegion
  }

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    CassandraLauncher.stop()
    Try(deleteFile(cassandraDirectory)).failed.foreach(_.printStackTrace)
    super.afterAll()
  }
}
