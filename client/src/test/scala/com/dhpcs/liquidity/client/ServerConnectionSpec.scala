package com.dhpcs.liquidity.client

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream}
import java.nio.file.Files
import java.security.cert.{CertificateException, X509Certificate}
import java.util.concurrent.Executors
import javax.net.ssl.{SSLContext, X509TrustManager}

import akka.NotUsed
import akka.actor.{Actor, ActorPath, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model.{RemoteAddress, ws}
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.stream.scaladsl.{Flow, Keep, Source}
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{ActorMaterializer, OverflowStrategy, TLSClientAuth}
import akka.testkit.{TestActor, TestKit, TestProbe}
import com.dhpcs.liquidity.certgen.CertGen
import com.dhpcs.liquidity.client.ServerConnection._
import com.dhpcs.liquidity.client.ServerConnectionSpec._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.serialization.ProtoConverter
import com.dhpcs.liquidity.server.LiquidityServer._
import com.dhpcs.liquidity.server._
import com.dhpcs.liquidity.server.actor.ClientConnectionActor._
import com.dhpcs.liquidity.server.actor.{ClientConnectionActor, LegacyClientConnectionActor}
import com.dhpcs.liquidity.ws.protocol._
import com.typesafe.config.ConfigFactory
import org.json4s.JValue
import org.json4s.JsonAST.{JInt, JObject}
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Second, Seconds, Span}

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}

object ServerConnectionSpec {

  object ClientConnectionTestProbeForwarderActor {
    def props(clientConnectionActorTestProbe: ActorRef)(upstream: ActorRef): Props =
      Props(new ClientConnectionTestProbeForwarderActor(clientConnectionActorTestProbe, upstream))
  }

  class ClientConnectionTestProbeForwarderActor(clientConnectionActorTestProbe: ActorRef, upstream: ActorRef)
      extends Actor {
    override def receive: Receive = {
      case message if sender() == clientConnectionActorTestProbe =>
        upstream ! message
      case message =>
        sender() ! ActorSinkAck
        clientConnectionActorTestProbe ! message
    }
  }
}

class ServerConnectionSpec
    extends fixture.FreeSpec
    with HttpController
    with BeforeAndAfterAll
    with ScalaFutures
    with Inside {

  private[this] val akkaHttpPort = freePort()

  private[this] val config = ConfigFactory
    .parseString("""
      |akka {
      |  loglevel = "ERROR"
      |  http.server {
      |    remote-address-header = on
      |    parsing.tls-session-info-header = on
      |  }
      |}
    """.stripMargin)
    .resolve()

  private[this] implicit val system = ActorSystem("liquidity", config)
  private[this] implicit val mat    = ActorMaterializer()

  private[this] val clientConnectionActorTestProbe = TestProbe()

  private[this] val (serverCertificate, serverKeyManagers) = {
    val (certificate, privateKey) = CertGen.generateCertKey(subjectAlternativeName = Some("localhost"))
    (certificate, createKeyManagers(certificate, privateKey))
  }

  private[this] val httpsConnectionContext = {
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(
      serverKeyManagers,
      Array(new X509TrustManager {

        override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = ()

        override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit =
          throw new CertificateException

        override def getAcceptedIssuers: Array[X509Certificate] = Array.empty

      }),
      null
    )
    ConnectionContext.https(
      sslContext,
      enabledCipherSuites = Some(EnabledCipherSuites),
      enabledProtocols = Some(EnabledProtocols),
      clientAuth = Some(TLSClientAuth.Want)
    )
  }

  override protected[this] def webSocketApi(ip: RemoteAddress,
                                            publicKey: PublicKey): Flow[ws.Message, ws.Message, NotUsed] =
    ClientConnectionActor.webSocketFlow(
      props = ClientConnectionTestProbeForwarderActor.props(clientConnectionActorTestProbe.ref),
      name = publicKey.fingerprint
    )

  override protected[this] def legacyWebSocketApi(ip: RemoteAddress,
                                                  publicKey: PublicKey): Flow[ws.Message, ws.Message, NotUsed] =
    LegacyClientConnectionActor.webSocketFlow(
      props = ClientConnectionTestProbeForwarderActor.props(clientConnectionActorTestProbe.ref),
      name = publicKey.fingerprint
    )

  override protected[this] def getStatus: Future[JValue] =
    Future.successful(
      JObject(
        "clients"         -> JObject(),
        "totalZonesCount" -> JInt(0),
        "activeZones"     -> JObject(),
        "shardRegions"    -> JObject(),
        "clusterSharding" -> JObject()
      ))
  override protected[this] def getZone(zoneId: ZoneId): Future[Option[Zone]] = Future.successful(None)
  override protected[this] def getBalances(zoneId: ZoneId): Future[Map[AccountId, BigDecimal]] =
    Future.successful(Map.empty)
  override protected[this] def getClients(zoneId: ZoneId): Future[Map[ActorPath, (Long, PublicKey)]] =
    Future.successful(Map.empty)

  private[this] val binding = Http().bindAndHandle(
    route(enableClientRelay = true),
    "0.0.0.0",
    akkaHttpPort,
    httpsConnectionContext
  )

  private[this] val filesDir = Files.createTempDirectory("liquidity-server-connection-spec-files-dir")

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

  private[this] val serverConnection = new ServerConnection(
    filesDir.toFile,
    keyStoreInputStreamProvider,
    connectivityStatePublisherBuilder,
    handlerWrapperFactory,
    hostname = "localhost",
    port = akkaHttpPort
  )

  private[this] def send(command: Command): Future[Response] = {
    val promise = Promise[Response]
    MainHandlerWrapper.post(
      serverConnection.sendCommand(
        command,
        new ResponseCallback {
          override def onErrorResponse(errorResponse: ErrorResponse): Unit       = promise.success(errorResponse)
          override def onSuccessResponse(successResponse: SuccessResponse): Unit = promise.success(successResponse)
        }
      ))
    promise.future
  }

  override protected def afterAll(): Unit = {
    handlerWrapperFactory.synchronized(handlerWrappers.foreach(_.quit()))
    delete(filesDir)
    Await.result(binding.flatMap(_.unbind()), Duration.Inf)
    TestKit.shutdownActorSystem(system)
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
      MainHandlerWrapper.post(serverConnection.requestConnection(connectionRequestToken, retry = false))
      sub.requestNext(CONNECTING)
      sub.requestNext(ONLINE)
      MainHandlerWrapper.post(serverConnection.unrequestConnection(connectionRequestToken))
      sub.requestNext(DISCONNECTING)
      sub.requestNext(AVAILABLE)
    }
    "will complete with a CreateZoneResponse when forwarding a CreateZoneCommand" in { sub =>
      val createZoneCommand = CreateZoneCommand(
        equityOwnerPublicKey = serverConnection.clientKey,
        equityOwnerName = Some("Dave"),
        equityOwnerMetadata = None,
        equityAccountName = None,
        equityAccountMetadata = None,
        name = Some("Dave's Game")
      )
      val created = System.currentTimeMillis
      val expires = created + 2.days.toMillis
      val createZoneResponse = CreateZoneResponse(
        zone = Zone(
          id = ZoneId.generate,
          equityAccountId = AccountId(0),
          members =
            Map(MemberId(0)           -> Member(MemberId(0), ownerPublicKey = serverConnection.clientKey, name = Some("Dave"))),
          accounts = Map(AccountId(0) -> Account(AccountId(0), ownerMemberIds = Set(MemberId(0)))),
          transactions = Map.empty,
          created,
          expires,
          name = Some("Dave's Game")
        )
      )
      clientConnectionActorTestProbe.setAutoPilot((sender: ActorRef, msg: Any) =>
        msg match {
          case ActorSinkInit =>
            (sender: ActorRef, msg: Any) =>
              {
                msg match {
                  case WrappedProtobufCommand(protobufCommand) =>
                    assert(protobufCommand.correlationId === 0L)
                    // TODO: DRY
                    inside(protobufCommand.command) {
                      case proto.ws.protocol.Command.Command.ZoneCommand(protobufZoneCommand) =>
                        assert(
                          ProtoConverter[ZoneCommand, proto.ws.protocol.ZoneCommand.ZoneCommand]
                            .asScala(protobufZoneCommand.zoneCommand)
                            === createZoneCommand)
                    }
                    sender.tell(
                      WrappedProtobufResponse(
                        // TODO: DRY
                        proto.ws.protocol.ResponseOrNotification.Response(
                          protobufCommand.correlationId,
                          proto.ws.protocol.ResponseOrNotification.Response.Response.ZoneResponse(
                            proto.ws.protocol.ZoneResponse(
                              ProtoConverter[ZoneResponse, proto.ws.protocol.ZoneResponse.ZoneResponse]
                                .asProto(createZoneResponse)
                            )
                          )
                        )
                      ),
                      sender = clientConnectionActorTestProbe.ref
                    )
                }
                TestActor.NoAutoPilot
              }
      })
      val connectionRequestToken = new ConnectionRequestToken
      sub.requestNext(AVAILABLE)
      MainHandlerWrapper.post(serverConnection.requestConnection(connectionRequestToken, retry = false))
      sub.requestNext(CONNECTING)
      sub.requestNext(ONLINE)
      assert(send(createZoneCommand).futureValue === createZoneResponse)
      MainHandlerWrapper.post(serverConnection.unrequestConnection(connectionRequestToken))
      sub.requestNext(DISCONNECTING)
      sub.requestNext(AVAILABLE)
    }
  }
}
