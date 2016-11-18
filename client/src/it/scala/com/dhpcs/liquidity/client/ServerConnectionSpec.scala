package com.dhpcs.liquidity.client

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream}
import java.nio.file.Files
import java.security.Security
import java.util.concurrent.Executors

import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Keep, Source}
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.scaladsl.TestSink
import com.dhpcs.jsonrpc.ResponseCompanion.ErrorResponse
import com.dhpcs.liquidity.certgen.CertGen
import com.dhpcs.liquidity.client.ServerConnection._
import com.dhpcs.liquidity.model.{Member, MemberId}
import com.dhpcs.liquidity.protocol.{Command, CreateZoneCommand, CreateZoneResponse, ResultResponse}
import com.dhpcs.liquidity.server.{CassandraPersistenceIntegrationTestFixtures, LiquidityServer}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Second, Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, Inside, Matchers, fixture}
import org.spongycastle.jce.provider.BouncyCastleProvider

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, Promise}

class ServerConnectionSpec
    extends fixture.WordSpec
    with CassandraPersistenceIntegrationTestFixtures
    with Matchers
    with ScalaFutures
    with Inside
    with BeforeAndAfterAll {

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
    Security.addProvider(new BouncyCastleProvider)
  }

  override protected def afterAll(): Unit = {
    handlerWrapperFactory.synchronized(handlerWrappers.foreach(_.quit()))
    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
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
