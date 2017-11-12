package com.dhpcs.liquidity.client

import java.io.IOException
import java.net.InetAddress
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor}
import java.util.UUID
import java.util.concurrent.Executors

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws
import akka.stream.scaladsl.{Flow, Keep, Source}
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.testkit.TestProbe
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.adapter._
import akka.typed.{ActorRef, Behavior}
import cats.data.Validated
import com.dhpcs.liquidity.actor.protocol.clientconnection._
import com.dhpcs.liquidity.actor.protocol.clientmonitor.ActiveClientSummary
import com.dhpcs.liquidity.actor.protocol.zonemonitor._
import com.dhpcs.liquidity.client.ServerConnection._
import com.dhpcs.liquidity.client.ServerConnectionSpec._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.server.HttpController.EventEnvelope
import com.dhpcs.liquidity.server._
import com.dhpcs.liquidity.server.actor.ClientConnectionActor
import com.dhpcs.liquidity.testkit.TestKit
import com.dhpcs.liquidity.ws.protocol.ProtoBindings._
import com.dhpcs.liquidity.ws.protocol._
import com.typesafe.config.ConfigFactory
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Second, Seconds, Span}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

object ServerConnectionSpec {
  object ClientConnectionTestProbeForwarderActor {

    def behavior(clientConnectionActorTestProbe: akka.actor.ActorRef)(
        webSocketOut: ActorRef[proto.ws.protocol.ClientMessage]): Behavior[Any] =
      Actor.immutable((context, message) =>
        message match {
          case actorSinkInit @ InitActorSink(webSocketIn) =>
            webSocketIn ! ActorSinkAck
            clientConnectionActorTestProbe.tell(actorSinkInit, context.self.toUntyped)
            Actor.same

          case ActorFlowServerMessage(webSocketIn, serverMessage) =>
            webSocketIn ! ActorSinkAck
            clientConnectionActorTestProbe.tell(serverMessage, context.self.toUntyped)
            Actor.same

          case clientMessage: proto.ws.protocol.ClientMessage =>
            webSocketOut ! clientMessage
            Actor.same
      })

  }
}

class ServerConnectionSpec
    extends fixture.FreeSpec
    with HttpController
    with BeforeAndAfterAll
    with ScalaFutures
    with Inside {

  private[this] val akkaHttpPort = TestKit.freePort()

  private[this] val config = ConfigFactory
    .parseString("""
      |akka {
      |  loglevel = "WARNING"
      |  http.server.remote-address-header = on
      |}
    """.stripMargin)
    .resolve()

  private[this] implicit val system: ActorSystem    = ActorSystem("liquidity", config)
  private[this] implicit val mat: ActorMaterializer = ActorMaterializer()
  private[this] implicit val ec: ExecutionContext   = system.dispatcher

  private[this] val clientConnectionActorTestProbe = TestProbe()

  override protected[this] def events(persistenceId: String,
                                      fromSequenceNr: Long,
                                      toSequenceNr: Long): Source[EventEnvelope, NotUsed] = Source.empty[EventEnvelope]
  override protected[this] def zoneState(zoneId: ZoneId): Future[proto.persistence.zone.ZoneState] =
    Future.successful(proto.persistence.zone.ZoneState(zone = None, balances = Map.empty, connectedClients = Map.empty))

  override protected[this] def webSocketApi(remoteAddress: InetAddress): Flow[ws.Message, ws.Message, NotUsed] =
    ClientConnectionActor.webSocketFlow(
      behavior = ClientConnectionTestProbeForwarderActor.behavior(clientConnectionActorTestProbe.ref)
    )

  override protected[this] def getActiveClientSummaries: Future[Set[ActiveClientSummary]] = Future.successful(Set.empty)
  override protected[this] def getActiveZoneSummaries: Future[Set[ActiveZoneSummary]]     = Future.successful(Set.empty)
  override protected[this] def getZone(zoneId: ZoneId): Future[Option[Zone]]              = Future.successful(None)
  override protected[this] def getBalances(zoneId: ZoneId): Future[Map[AccountId, BigDecimal]] =
    Future.successful(Map.empty)
  override protected[this] def getZoneCount: Future[Long]        = Future.successful(0)
  override protected[this] def getPublicKeyCount: Future[Long]   = Future.successful(0)
  override protected[this] def getMemberCount: Future[Long]      = Future.successful(0)
  override protected[this] def getAccountCount: Future[Long]     = Future.successful(0)
  override protected[this] def getTransactionCount: Future[Long] = Future.successful(0)

  private[this] val binding = Http().bindAndHandle(
    httpRoutes(enableClientRelay = true),
    "0.0.0.0",
    akkaHttpPort
  )

  private[this] val filesDir = Files.createTempDirectory("liquidity-server-connection-spec-files-dir")

  private[this] val mainThreadExecutorService = Executors.newSingleThreadExecutor()
  private[this] val serverConnection = new ServerConnection(
    filesDir.toFile,
    connectivityStatePublisherProvider = new ConnectivityStatePublisherProvider {
      override def provide(serverConnection: ServerConnection): ConnectivityStatePublisher =
        new ConnectivityStatePublisher {
          override def isConnectionAvailable: Boolean = true
          override def register(): Unit               = ()
          override def unregister(): Unit             = ()
        }
    },
    mainThreadExecutorService,
    scheme = "http",
    hostname = "localhost",
    port = akkaHttpPort
  )

  override protected def afterAll(): Unit = {
    mainThreadExecutorService.shutdown()
    Files.walkFileTree(
      filesDir,
      new SimpleFileVisitor[Path]() {
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
          Files.delete(file); FileVisitResult.CONTINUE
        }
        override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
          Files.delete(dir); FileVisitResult.CONTINUE
        }
      }
    )
    Await.result(binding.flatMap(_.unbind()), Duration.Inf)
    akka.testkit.TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  override protected type FixtureParam = TestSubscriber.Probe[ConnectionState]

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(5, Seconds)), interval = scaled(Span(1, Second)))

  override protected def withFixture(test: OneArgTest): Outcome = {
    val (queue, sub) = Source
      .queue[ConnectionState](bufferSize = 0, overflowStrategy = OverflowStrategy.backpressure)
      .toMat(TestSink.probe[ServerConnection.ConnectionState])(Keep.both)
      .run()
    val connectionStateListener = new ConnectionStateListener {
      override def onConnectionStateChanged(connectionState: ConnectionState): Unit = {
        queue.offer(connectionState); ()
      }
    }
    serverConnection.registerListener(connectionStateListener)
    try withFixture(test.toNoArgTest(sub))
    finally {
      serverConnection.unregisterListener(connectionStateListener)
      sub.cancel(); ()
    }
  }

  "ServerConnection" - {
    "will connect to the server and update the connection state as it does so" in { sub =>
      val connectionRequestToken = new ConnectionRequestToken
      sub.requestNext(AVAILABLE)
      mainThreadExecutorService.submit(serverConnection.requestConnection(connectionRequestToken, retry = false))
      sub.requestNext(CONNECTING)
      clientConnectionActorTestProbe.expectMsgType[InitActorSink]
      sub.requestNext(AUTHENTICATING)
      val keyOwnershipChallenge = Authentication.createKeyOwnershipChallengeMessage()
      clientConnectionActorTestProbe
        .sender()
        .tell(
          proto.ws.protocol
            .ClientMessage(proto.ws.protocol.ClientMessage.Message.KeyOwnershipChallenge(keyOwnershipChallenge)),
          sender = clientConnectionActorTestProbe.ref
        )
      inside(clientConnectionActorTestProbe.expectMsgType[proto.ws.protocol.ServerMessage].message) {
        case proto.ws.protocol.ServerMessage.Message.KeyOwnershipProof(keyOwnershipProof) =>
          assert(Authentication.isValidKeyOwnershipProof(keyOwnershipChallenge, keyOwnershipProof))
      }
      sub.requestNext(ONLINE)
      mainThreadExecutorService.submit(serverConnection.unrequestConnection(connectionRequestToken))
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
        Validated.valid(
          Zone(
            id = ZoneId(UUID.randomUUID.toString),
            equityAccountId = AccountId("0"),
            members = Map(
              MemberId("0") -> Member(MemberId("0"),
                                      ownerPublicKeys = Set(serverConnection.clientKey),
                                      name = Some("Dave"))
            ),
            accounts = Map(
              AccountId("0") -> Account(AccountId("0"), ownerMemberIds = Set(MemberId("0")))
            ),
            transactions = Map.empty,
            created,
            expires,
            name = Some("Dave's Game")
          ))
      )
      val connectionRequestToken = new ConnectionRequestToken
      sub.requestNext(AVAILABLE)
      mainThreadExecutorService.submit(serverConnection.requestConnection(connectionRequestToken, retry = false))
      sub.requestNext(CONNECTING)
      clientConnectionActorTestProbe.expectMsgType[InitActorSink]
      sub.requestNext(AUTHENTICATING)
      val keyOwnershipChallenge = Authentication.createKeyOwnershipChallengeMessage()
      clientConnectionActorTestProbe
        .sender()
        .tell(
          proto.ws.protocol
            .ClientMessage(proto.ws.protocol.ClientMessage.Message.KeyOwnershipChallenge(keyOwnershipChallenge)),
          sender = clientConnectionActorTestProbe.ref
        )
      inside(clientConnectionActorTestProbe.expectMsgType[proto.ws.protocol.ServerMessage].message) {
        case proto.ws.protocol.ServerMessage.Message.KeyOwnershipProof(keyOwnershipProof) =>
          assert(Authentication.isValidKeyOwnershipProof(keyOwnershipChallenge, keyOwnershipProof))
      }
      sub.requestNext(ONLINE)
      val response = serverConnection.sendCreateZoneCommand(createZoneCommand)
      inside(clientConnectionActorTestProbe.expectMsgType[proto.ws.protocol.ServerMessage].message) {
        case proto.ws.protocol.ServerMessage.Message.Command(protoCommand) =>
          assert(protoCommand.correlationId === 0L)
          inside(protoCommand.command) {
            case proto.ws.protocol.ServerMessage.Command.Command.CreateZoneCommand(protoCreateZoneCommand) =>
              assert(
                ProtoBinding[CreateZoneCommand, proto.ws.protocol.ZoneCommand.CreateZoneCommand, Any]
                  .asScala(protoCreateZoneCommand)(()) === createZoneCommand
              )
          }
      }
      clientConnectionActorTestProbe
        .sender()
        .tell(
          proto.ws.protocol.ClientMessage(
            proto.ws.protocol.ClientMessage.Message.Response(proto.ws.protocol.ClientMessage.Response(
              correlationId = 0L,
              proto.ws.protocol.ClientMessage.Response.Response.ZoneResponse(proto.ws.protocol.ZoneResponse(
                ProtoBinding[ZoneResponse, proto.ws.protocol.ZoneResponse, Any]
                  .asProto(createZoneResponse)(())
                  .zoneResponse
              ))
            ))),
          sender = clientConnectionActorTestProbe.ref
        )
      assert(response.futureValue === createZoneResponse)
      mainThreadExecutorService.submit(serverConnection.unrequestConnection(connectionRequestToken))
      sub.requestNext(DISCONNECTING)
      sub.requestNext(AVAILABLE)
    }
  }
}
