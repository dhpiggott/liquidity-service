package com.dhpcs.liquidity.client

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream}
import java.nio.file.Files
import java.security.Security
import java.util.concurrent.Executors

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
import com.dhpcs.liquidity.certgen.CertGen
import com.dhpcs.liquidity.client.ServerConnection._
import com.dhpcs.liquidity.model.{Member, MemberId}
import com.dhpcs.liquidity.server._
import com.dhpcs.liquidity.server.actor.ZoneValidatorActor
import com.dhpcs.liquidity.ws.protocol._
import com.typesafe.config.ConfigFactory
import org.apache.cassandra.io.util.FileUtils
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Second, Seconds, Span}
import org.spongycastle.jce.provider.BouncyCastleProvider

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

class ServerConnectionSpec extends fixture.FreeSpec with BeforeAndAfterAll with ScalaFutures with Inside {

  private[this] val cassandraDirectory = FileUtils.createTempFile("liquidity-cassandra-data", null)
  private[this] val akkaRemotingPort   = freePort()
  private[this] val akkaHttpPort       = freePort()
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
           |    serializers {
           |      client-connection-protocol = "com.dhpcs.liquidity.actor.protocol.ClientConnectionMessageSerializer"
           |      zone-validator-protocol = "com.dhpcs.liquidity.actor.protocol.ZoneValidatorMessageSerializer"
           |      persistence-event = "com.dhpcs.liquidity.persistence.EventSerializer"
           |    }
           |    serialization-bindings {
           |      "com.dhpcs.liquidity.actor.protocol.ClientConnectionMessage" = client-connection-protocol
           |      "com.dhpcs.liquidity.actor.protocol.ZoneValidatorMessage" = zone-validator-protocol
           |      "com.dhpcs.liquidity.persistence.Event" = persistence-event
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
           |    auto-down-unreachable-after = 5s
           |    metrics.enabled = off
           |    roles = ["zone-host", "client-relay"]
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
           |  keep-alive-interval = 3s
           |  interface = "0.0.0.0"
           |  port = "$akkaHttpPort"
           |}
    """.stripMargin
      )
      .resolve()

  private[this] implicit val system = ActorSystem("liquidity", config)
  private[this] implicit val mat    = ActorMaterializer()

  private[this] val readJournal =
    PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

  private[this] val futureAnalyticsStore =
    readJournal.session
      .underlying()
      .flatMap(CassandraAnalyticsStore(system.settings.config)(_, ExecutionContext.global))(ExecutionContext.global)

  private[this] val zoneValidatorShardRegion = ClusterSharding(system).start(
    typeName = ZoneValidatorActor.ShardTypeName,
    entityProps = ZoneValidatorActor.props,
    settings = ClusterShardingSettings(system),
    extractEntityId = ZoneValidatorActor.extractEntityId,
    extractShardId = ZoneValidatorActor.extractShardId
  )

  private[this] val (serverCertificate, serverKeyManagers) = {
    val (certificate, privateKey) = CertGen.generateCertKey(subjectAlternativeName = Some("localhost"))
    (certificate, createKeyManagers(certificate, privateKey))
  }

  private[this] val server = new LiquidityServer(
    config,
    readJournal,
    futureAnalyticsStore,
    zoneValidatorShardRegion,
    serverKeyManagers
  )

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
    CertGen.saveCert(to, "PKCS12", serverCertificate)
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

  private[this] class HandlerWrapperImpl extends HandlerWrapper {

    private[this] val executor = Executors.newSingleThreadExecutor()

    override def post(runnable: Runnable): Unit = executor.submit(runnable)
    override def quit(): Unit                   = executor.shutdown()

  }

  private[this] object MainHandlerWrapper extends HandlerWrapperImpl

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
    hostname = Some("localhost"),
    port = Some(akkaHttpPort)
  )

  private[this] def send(command: Command): Future[Response] = {
    val promise = Promise[Response]
    MainHandlerWrapper.post(
      () =>
        serverConnection.sendCommand(
          command,
          new ResponseCallback {
            override def onErrorResponse(errorResponse: ErrorResponse): Unit       = promise.success(errorResponse)
            override def onSuccessResponse(successResponse: SuccessResponse): Unit = promise.success(successResponse)
          }
      ))
    promise.future
  }

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
    Await.result(server.shutdown(), Duration.Inf)
    TestKit.shutdownActorSystem(system)
    CassandraLauncher.stop()
    FileUtils.deleteRecursive(cassandraDirectory)
    super.afterAll()
  }

  override protected type FixtureParam = TestSubscriber.Probe[ConnectionState]

  implicit override val patienceConfig =
    PatienceConfig(timeout = scaled(Span(5, Seconds)), interval = scaled(Span(1, Second)))

  override protected def withFixture(test: OneArgTest): Outcome = {
    val (queue, sub) = Source
      .queue[ConnectionState](bufferSize = 0, overflowStrategy = OverflowStrategy.backpressure)
      .toMat(TestSink.probe[ServerConnection.ConnectionState])(Keep.both)
      .run()
    val connectionStateListener = new ConnectionStateListener {
      override def onConnectionStateChanged(connectionState: ConnectionState): Unit = queue.offer(connectionState)
    }
    serverConnection.registerListener(connectionStateListener)
    try withFixture(test.toNoArgTest(sub))
    finally {
      serverConnection.unregisterListener(connectionStateListener)
      sub.cancel()
    }
  }

  "ServerConnection" - {
    "will connect to the server and update the connection state as it does so" in { sub =>
      val connectionRequestToken = new ConnectionRequestToken
      sub.requestNext(AVAILABLE)
      MainHandlerWrapper.post(() => serverConnection.requestConnection(connectionRequestToken, retry = false))
      sub.requestNext(CONNECTING)
      sub.requestNext(WAITING_FOR_VERSION_CHECK)
      sub.requestNext(ONLINE)
      MainHandlerWrapper.post(() => serverConnection.unrequestConnection(connectionRequestToken))
      sub.requestNext(DISCONNECTING)
      sub.requestNext(AVAILABLE)
    }
    "will complete with a CreateZoneResponse when forwarding a CreateZoneCommand" in { sub =>
      val connectionRequestToken = new ConnectionRequestToken
      sub.requestNext(AVAILABLE)
      MainHandlerWrapper.post(() => serverConnection.requestConnection(connectionRequestToken, retry = false))
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
        case CreateZoneResponse(zone) =>
          assert(zone.members(MemberId(0)) === Member(MemberId(0), serverConnection.clientKey, name = Some("Dave")))
          assert(zone.name === Some("Dave's Game"))
      }
      MainHandlerWrapper.post(() => serverConnection.unrequestConnection(connectionRequestToken))
      sub.requestNext(DISCONNECTING)
      sub.requestNext(AVAILABLE)
    }
  }
}
