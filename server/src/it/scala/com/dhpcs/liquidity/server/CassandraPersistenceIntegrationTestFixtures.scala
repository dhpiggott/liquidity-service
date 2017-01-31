package com.dhpcs.liquidity.server

import java.io.File
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.security.cert.Certificate
import java.security.{KeyStore, PrivateKey}
import javax.net.ssl.{KeyManager, KeyManagerFactory}

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.cassandra.testkit.CassandraLauncher
import akka.persistence.query.PersistenceQuery
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import com.dhpcs.liquidity.certgen.CertGen
import com.dhpcs.liquidity.server.actors.ZoneValidatorActor
import com.dhpcs.liquidity.server.CassandraPersistenceIntegrationTestFixtures._
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.cassandra.io.util.FileUtils
import org.scalatest.BeforeAndAfterAll
import org.scalatest.fixture

object CassandraPersistenceIntegrationTestFixtures {

  protected final val KeyStoreEntryAlias                 = "identity"
  protected final val KeyStoreEntryPassword: Array[Char] = Array.emptyCharArray

}

trait CassandraPersistenceIntegrationTestFixtures extends BeforeAndAfterAll { this: fixture.Suite =>

  protected[this] val akkaRemotingPort: Int = freePort()
  protected[this] val akkaHttpPort: Int     = freePort()

  protected[this] def freePort(): Int = {
    val serverSocket = ServerSocketChannel.open().socket()
    serverSocket.bind(new InetSocketAddress("localhost", 0))
    val port = serverSocket.getLocalPort
    serverSocket.close()
    port
  }

  protected[this] val cassandraDirectory: File = FileUtils.createTempFile("liquidity-cassandra-data", null)

  protected[this] val config: Config =
    ConfigFactory
      .parseString(
        s"""
         |akka {
         |  loggers = ["akka.event.slf4j.Slf4jLogger"]
         |  loglevel = "DEBUG"
         |  logging-filter = "akka.event.slf4j.Slf4jLoggingFilter"
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
         |  extensions += "akka.persistence.Persistence"
         |  persistence.journal {
         |    auto-start-journals = ["cassandra-journal"]
         |    plugin = "cassandra-journal"
         |  }
         |  http.server {
         |    remote-address-header = on
         |    parsing.tls-session-info-header = on
         |  }
         |}
         |cassandra-journal.contact-points = ["localhost:${CassandraLauncher.randomPort}"]
         |liquidity.server.http {
         |  keep-alive-interval = "3 seconds"
         |  interface = "0.0.0.0"
         |  port = "$akkaHttpPort"
         |}
    """.stripMargin
      )
      .resolve()

  protected[this] implicit val system = ActorSystem("liquidity", config)
  protected[this] implicit val mat    = ActorMaterializer()

  protected[this] val readJournal: CassandraReadJournal =
    PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

  protected[this] val zoneValidatorShardRegion: ActorRef = ClusterSharding(system).start(
    typeName = ZoneValidatorActor.ShardTypeName,
    entityProps = ZoneValidatorActor.props,
    settings = ClusterShardingSettings(system),
    extractEntityId = ZoneValidatorActor.extractEntityId,
    extractShardId = ZoneValidatorActor.extractShardId
  )

  protected[this] val (serverCertificate, serverKeyManagers) = {
    val (certificate, privateKey) = CertGen.generateCertKey(subjectAlternativeName = Some("localhost"))
    (certificate, createKeyManagers(certificate, privateKey))
  }

  protected[this] def createKeyManagers(certificate: Certificate, privateKey: PrivateKey): Array[KeyManager] = {
    val keyStore = KeyStore.getInstance("PKCS12")
    keyStore.load(null, null)
    keyStore.setKeyEntry(
      KeyStoreEntryAlias,
      privateKey,
      KeyStoreEntryPassword,
      Array(certificate)
    )
    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keyManagerFactory.init(
      keyStore,
      Array.emptyCharArray
    )
    keyManagerFactory.getKeyManagers
  }

  override protected def beforeAll(): Unit = {
    CassandraLauncher.start(
      cassandraDirectory = cassandraDirectory,
      configResource = CassandraLauncher.DefaultTestConfigResource,
      clean = true,
      port = 0
    )
    super.beforeAll()
  }

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    CassandraLauncher.stop()
    FileUtils.deleteRecursive(cassandraDirectory)
    super.afterAll()
  }
}
