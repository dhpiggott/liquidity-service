package com.dhpcs.liquidity.client

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream}
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.security.cert.Certificate
import java.security.{KeyStore, PrivateKey, Security}
import java.util.concurrent.Executors
import javax.net.ssl.{KeyManager, KeyManagerFactory}

import akka.actor.ActorSystem
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.cassandra.testkit.CassandraLauncher
import akka.persistence.query.PersistenceQuery
import akka.stream.scaladsl.{Keep, Source}
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.testkit.TestKit
import com.dhpcs.jsonrpc.ResponseCompanion.ErrorResponse
import com.dhpcs.liquidity.certgen.CertGen
import com.dhpcs.liquidity.client.ServerConnection._
import com.dhpcs.liquidity.client.ServerConnectionSpec._
import com.dhpcs.liquidity.model.{Member, MemberId}
import com.dhpcs.liquidity.protocol.{Command, CreateZoneCommand, CreateZoneResponse, ResultResponse}
import com.dhpcs.liquidity.server.LiquidityServer
import com.dhpcs.liquidity.server.actors.ZoneValidatorActor
import com.typesafe.config.ConfigFactory
import org.apache.cassandra.io.util.FileUtils
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Second, Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, Inside, Matchers, fixture}
import org.spongycastle.jce.provider.BouncyCastleProvider

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, Promise}

object ServerConnectionSpec {

  private final val KeyStoreEntryAlias    = "identity"
  private final val KeyStoreEntryPassword = Array.emptyCharArray

  private def createKeyManagers(certificate: Certificate, privateKey: PrivateKey): Array[KeyManager] = {
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
}

class ServerConnectionSpec
    extends fixture.WordSpec
    with Matchers
    with ScalaFutures
    with Inside
    with BeforeAndAfterAll {

  private[this] val akkaRemotingPort = freePort()
  private[this] val akkaHttpPort     = freePort()

  private[this] def freePort(): Int = {
    val serverSocket = ServerSocketChannel.open().socket()
    serverSocket.bind(new InetSocketAddress("localhost", 0))
    val port = serverSocket.getLocalPort
    serverSocket.close()
    port
  }

  private[this] val cassandraDirectory = FileUtils.createTempFile("liquidity-cassandra-data", null)

  private[this] val config =
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
         |  persistence {
         |    journal.plugin = "cassandra-journal"
         |    snapshot-store.plugin = "cassandra-snapshot-store"
         |  }
         |  http.server {
         |    remote-address-header = on
         |    parsing.tls-session-info-header = on
         |  }
         |}
         |cassandra-journal.contact-points = ["localhost:${CassandraLauncher.randomPort}"]
         |cassandra-snapshot-store.contact-points = ["localhost:${CassandraLauncher.randomPort}"]
         |liquidity {
         |  http {
         |    keep-alive-interval = "3 seconds"
         |    interface = "0.0.0.0"
         |    port = "$akkaHttpPort"
         |  }
         |}
    """.stripMargin
      )
      .resolve()

  private[this] implicit val system = ActorSystem("liquidity", config)
  private[this] implicit val mat    = ActorMaterializer()

  private[this] val readJournal =
    PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

  private[this] val zoneValidatorShardRegion = ClusterSharding(system).start(
    typeName = ZoneValidatorActor.ShardName,
    entityProps = ZoneValidatorActor.props,
    settings = ClusterShardingSettings(system),
    extractEntityId = ZoneValidatorActor.extractEntityId,
    extractShardId = ZoneValidatorActor.extractShardId
  )

  private[this] val (certificate, privateKey) = CertGen.generateCertKey(subjectAlternativeName = Some("localhost"))

  private[this] val serverKeyManagers = {
    createKeyManagers(certificate, privateKey)
  }

  private[this] val prngFixesApplicator = new PRNGFixesApplicator {
    override def apply(): Unit = ()
  }

  private[this] val filesDirDirectory = {
    val filesDirDirectory = Files.createTempDirectory("liquidity-files-dir").toFile
    filesDirDirectory.deleteOnExit()
    filesDirDirectory
  }

  private[this] val keyStoreInputStreamProvider = {
    val to = new ByteArrayOutputStream
    CertGen.saveCert(to, "PKCS12", certificate)
    val keyStoreInputStream = new ByteArrayInputStream(to.toByteArray)
    new KeyStoreInputStreamProvider {
      override def get(): InputStream = keyStoreInputStream
    }
  }

  private[this] val connectivityStatePublisherBuilder = new ConnectivityStatePublisherBuilder {
    override def build(serverConnection: ServerConnection): ConnectivityStatePublisher =
      new ConnectivityStatePublisher {
        override def isConnectionAvailable: Boolean = true
        override def register(): Unit               = ()
        override def unregister(): Unit             = ()
      }
  }

  class HandlerWrapperImpl extends HandlerWrapper {

    private[this] val executor = Executors.newSingleThreadExecutor()

    override def post(runnable: Runnable): Unit = executor.submit(runnable)
    override def quit(): Unit                   = executor.shutdown()

  }

  object MainHandlerWrapper extends HandlerWrapperImpl

  private[this] var handlerWrappers = Seq[HandlerWrapper](MainHandlerWrapper)

  private[this] val handlerWrapperFactory = new HandlerWrapperFactory {
    override def create(name: String): HandlerWrapper = synchronized {
      val handlerWrapper = new HandlerWrapperImpl
      handlerWrappers = handlerWrapper +: handlerWrappers
      handlerWrapper
    }
    override def main(): HandlerWrapper = MainHandlerWrapper
  }

  private[this] val serverConnection = ServerConnection.getInstance(
    prngFixesApplicator,
    filesDirDirectory,
    keyStoreInputStreamProvider,
    connectivityStatePublisherBuilder,
    handlerWrapperFactory,
    serverEndpoint = s"https://localhost:$akkaHttpPort/ws"
  )

  private[this] def send(command: Command): Future[Either[ErrorResponse, ResultResponse]] = {
    val promise = Promise[Either[ErrorResponse, ResultResponse]]
    MainHandlerWrapper.post(serverConnection.sendCommand(command, new ResponseCallback {
      override def onErrorReceived(errorResponse: ErrorResponse): Unit    = promise.success(Left(errorResponse))
      override def onResultReceived(resultResponse: ResultResponse): Unit = promise.success(Right(resultResponse))
    }))
    promise.future
  }

  override protected type FixtureParam = TestSubscriber.Probe[ConnectionState]

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    CassandraLauncher.start(
      cassandraDirectory = cassandraDirectory,
      configResource = CassandraLauncher.DefaultTestConfigResource,
      clean = true,
      port = 0
    )
    Security.addProvider(new BouncyCastleProvider)
  }

  override protected def afterAll(): Unit = {
    handlerWrapperFactory.synchronized(handlerWrappers.foreach(_.quit()))
    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
    TestKit.shutdownActorSystem(system)
    CassandraLauncher.stop()
    FileUtils.deleteRecursive(cassandraDirectory)
    super.afterAll()
  }

  implicit override val patienceConfig =
    PatienceConfig(timeout = scaled(Span(5, Seconds)), interval = scaled(Span(1, Second)))

  override def withFixture(test: OneArgTest) = {
    val server = new LiquidityServer(
      config,
      readJournal,
      zoneValidatorShardRegion,
      serverKeyManagers
    )
    val (queue, sub) = Source
      .queue[ConnectionState](bufferSize = 0, overflowStrategy = OverflowStrategy.backpressure)
      .toMat(TestSink.probe)(Keep.both)
      .run()
    val connectionStateListener = new ConnectionStateListener {
      override def onConnectionStateChanged(connectionState: ConnectionState): Unit = queue.offer(connectionState)
    }
    serverConnection.registerListener(connectionStateListener)
    try withFixture(test.toNoArgTest(sub))
    finally {
      sub.cancel()
      serverConnection.unregisterListener(connectionStateListener)
      Await.result(server.shutdown(), Duration.Inf)
    }
  }

  "ServerConnection" should {
    "connect to the server and update the connection state as it does so" in { sub =>
      val connectionRequestToken = new ConnectionRequestToken
      sub.requestNext(AVAILABLE)
      MainHandlerWrapper.post(serverConnection.requestConnection(connectionRequestToken, retry = false))
      sub.requestNext(CONNECTING)
      sub.requestNext(WAITING_FOR_VERSION_CHECK)
      sub.requestNext(ONLINE)
      MainHandlerWrapper.post(serverConnection.unrequestConnection(connectionRequestToken))
      sub.requestNext(DISCONNECTING)
      sub.requestNext(AVAILABLE)
      succeed
    }
    "complete with a CreateZoneResponse when forwarding a CreateZoneCommand" in { sub =>
      val connectionRequestToken = new ConnectionRequestToken
      sub.requestNext(AVAILABLE)
      MainHandlerWrapper.post(serverConnection.requestConnection(connectionRequestToken, retry = false))
      sub.requestNext(CONNECTING)
      sub.requestNext(WAITING_FOR_VERSION_CHECK)
      sub.requestNext(ONLINE)
      val result = send(
        CreateZoneCommand(
          equityOwnerPublicKey = serverConnection.clientKey,
          equityOwnerName = Some("Dave"),
          equityOwnerMetadata = None,
          equityAccountName = None,
          equityAccountMetadata = None,
          name = Some("Dave's Game")
        )
      ).futureValue
      inside(result) {
        case Right(CreateZoneResponse(zone)) =>
          zone.members(MemberId(0)) shouldBe Member(MemberId(0), serverConnection.clientKey, name = Some("Dave"))
          zone.name shouldBe Some("Dave's Game")
      }
      MainHandlerWrapper.post(serverConnection.unrequestConnection(connectionRequestToken))
      sub.requestNext(DISCONNECTING)
      sub.requestNext(AVAILABLE)
    }
  }
}
