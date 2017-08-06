package com.dhpcs.liquidity.client

import java.nio.file.Files
import java.util.concurrent.Executors

import akka.NotUsed
import akka.actor.{Actor, ActorPath, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{RemoteAddress, ws}
import akka.stream.scaladsl.{Flow, Keep, Source}
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.testkit.{TestKit, TestProbe}
import cats.data.Validated
import com.dhpcs.liquidity.actor.protocol._
import com.dhpcs.liquidity.actor.protocol.ProtoBindings._
import com.dhpcs.liquidity.client.ServerConnection._
import com.dhpcs.liquidity.client.ServerConnectionSpec._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.model.ProtoBindings._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.proto.model.ZoneState
import com.dhpcs.liquidity.server.HttpController.GeneratedMessageEnvelope
import com.dhpcs.liquidity.server._
import com.dhpcs.liquidity.server.actor.ClientConnectionActor
import com.dhpcs.liquidity.server.actor.ClientConnectionActor._
import com.dhpcs.liquidity.ws.protocol._
import com.typesafe.config.ConfigFactory
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
      |  loglevel = "WARNING"
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

  override protected[this] def events(persistenceId: String,
                                      fromSequenceNr: Long,
                                      toSequenceNr: Long): Source[GeneratedMessageEnvelope, NotUsed] =
    Source.empty[GeneratedMessageEnvelope]

  override protected[this] def zoneState(zoneId: ZoneId): Future[ZoneState] =
    Future.successful(ZoneState(zone = None, balances = Map.empty, clientConnections = Map.empty))

  override protected[this] def webSocketApi(ip: RemoteAddress): Flow[ws.Message, ws.Message, NotUsed] =
    ClientConnectionActor.webSocketFlow(
      props = ClientConnectionTestProbeForwarderActor.props(clientConnectionActorTestProbe.ref)
    )

  override protected[this] def getActiveClientSummaries: Future[Set[ActiveClientSummary]] =
    Future.successful(Set.empty)

  override protected[this] def getActiveZoneSummaries: Future[Set[ActiveZoneSummary]] =
    Future.successful(Set.empty)

  override protected[this] def getZone(zoneId: ZoneId): Future[Option[Zone]] = Future.successful(None)

  override protected[this] def getBalances(zoneId: ZoneId): Future[Map[AccountId, BigDecimal]] =
    Future.successful(Map.empty)

  override protected[this] def getClients(zoneId: ZoneId): Future[Map[ActorPath, (Long, PublicKey)]] =
    Future.successful(Map.empty)

  private[this] val binding = Http().bindAndHandle(
    httpRoutes(enableClientRelay = true),
    "0.0.0.0",
    akkaHttpPort
  )

  private[this] val filesDir = Files.createTempDirectory("liquidity-server-connection-spec-files-dir")

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

    override def post(runnable: Runnable): Unit = {
      executor.submit(runnable); ()
    }

    override def quit(): Unit = executor.shutdown()

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
    connectivityStatePublisherBuilder,
    handlerWrapperFactory,
    scheme = "http",
    hostname = "localhost",
    port = akkaHttpPort
  )

  private[this] def sendCreateZoneCommand(createZoneCommand: CreateZoneCommand): Future[ZoneResponse] = {
    val promise = Promise[ZoneResponse]
    MainHandlerWrapper.post(
      serverConnection.sendCreateZoneCommand(
        createZoneCommand,
        promise.success _
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
      MainHandlerWrapper.post(serverConnection.requestConnection(connectionRequestToken, retry = false))
      sub.requestNext(CONNECTING)
      clientConnectionActorTestProbe.expectMsg(ActorSinkInit)
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
        Validated.valid(
          Zone(
            id = ZoneId.generate,
            equityAccountId = AccountId(0),
            members = Map(
              MemberId(0) -> Member(MemberId(0), ownerPublicKey = serverConnection.clientKey, name = Some("Dave"))
            ),
            accounts = Map(
              AccountId(0) -> Account(AccountId(0), ownerMemberIds = Set(MemberId(0)))
            ),
            transactions = Map.empty,
            created,
            expires,
            name = Some("Dave's Game")
          ))
      )
      val connectionRequestToken = new ConnectionRequestToken
      sub.requestNext(AVAILABLE)
      MainHandlerWrapper.post(serverConnection.requestConnection(connectionRequestToken, retry = false))
      sub.requestNext(CONNECTING)
      clientConnectionActorTestProbe.expectMsg(ActorSinkInit)
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
      val response = sendCreateZoneCommand(createZoneCommand)
      inside(clientConnectionActorTestProbe.expectMsgType[proto.ws.protocol.ServerMessage].message) {
        case proto.ws.protocol.ServerMessage.Message.Command(protoCommand) =>
          assert(protoCommand.correlationId === 0L)
          inside(protoCommand.command) {
            case proto.ws.protocol.ServerMessage.Command.Command.CreateZoneCommand(protoCreateZoneCommand) =>
              assert(
                ProtoBinding[CreateZoneCommand, proto.actor.protocol.ZoneCommand.CreateZoneCommand, Any]
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
              proto.ws.protocol.ClientMessage.Response.Response.ZoneResponse(proto.actor.protocol.ZoneResponse(
                ProtoBinding[ZoneResponse, proto.actor.protocol.ZoneResponse.ZoneResponse, Any]
                  .asProto(createZoneResponse)
              ))
            ))),
          sender = clientConnectionActorTestProbe.ref
        )
      assert(response.futureValue === createZoneResponse)
      MainHandlerWrapper.post(serverConnection.unrequestConnection(connectionRequestToken))
      sub.requestNext(DISCONNECTING)
      sub.requestNext(AVAILABLE)
    }
  }
}
