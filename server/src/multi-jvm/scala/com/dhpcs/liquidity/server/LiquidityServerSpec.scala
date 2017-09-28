package com.dhpcs.liquidity.server

import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.NotUsed
import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.cluster.MemberStatus.Up
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{BinaryMessage, WebSocketRequest, Message => WsMessage}
import akka.persistence.cassandra.testkit.CassandraLauncher
import akka.remote.testconductor.RoleName
import akka.remote.testkit.{MultiNodeConfig, MultiNodeSpec, MultiNodeSpecCallbacks}
import akka.stream.scaladsl.{Flow, Keep, Source}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import akka.stream.testkit.{TestPublisher, TestSubscriber}
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.ByteString
import cats.data.Validated
import com.dhpcs.liquidity.model.ProtoBindings._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.testkit.TestKit
import com.dhpcs.liquidity.ws.protocol.ProtoBindings._
import com.dhpcs.liquidity.ws.protocol._
import com.typesafe.config.ConfigFactory
import org.scalactic.TripleEqualsSupport.Spread
import org.scalatest.{BeforeAndAfterAll, FreeSpecLike, Inside}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

object LiquidityServerSpecConfig extends MultiNodeConfig {

  val cassandraNode: RoleName   = role("cassandra-node")
  val zoneHostNode: RoleName    = role("zone-host")
  val clientRelayNode: RoleName = role("client-relay")

  commonConfig(ConfigFactory.parseString(s"""
       |akka {
       |  actor {
       |    provider = "akka.cluster.ClusterActorRefProvider"
       |    serializers {
       |      zone-record = "com.dhpcs.liquidity.server.serialization.ZoneRecordSerializer"
       |      zone-validator-message = "com.dhpcs.liquidity.server.serialization.ZoneValidatorMessageSerializer"
       |      zone-monitor-message = "com.dhpcs.liquidity.server.serialization.ZoneMonitorMessageSerializer"
       |      client-connection-message = "com.dhpcs.liquidity.server.serialization.ClientConnectionMessageSerializer"
       |      client-monitor-message = "com.dhpcs.liquidity.server.serialization.ClientMonitorMessageSerializer"
       |    }
       |    serialization-bindings {
       |      "com.dhpcs.liquidity.persistence.zone.ZoneRecord" = zone-record
       |      "com.dhpcs.liquidity.actor.protocol.zonevalidator.ZoneValidatorMessage" = zone-validator-message
       |      "com.dhpcs.liquidity.actor.protocol.zonemonitor.ZoneMonitorMessage" = zone-monitor-message
       |      "com.dhpcs.liquidity.actor.protocol.clientconnection.ClientConnectionMessage" = client-connection-message
       |      "com.dhpcs.liquidity.actor.protocol.clientmonitor.ClientMonitorMessage" = client-monitor-message
       |    }
       |    allow-java-serialization = off
       |  }
       |  cluster.metrics.enabled = off
       |  extensions += "akka.persistence.Persistence"
       |  persistence {
       |    journal {
       |      auto-start-journals = ["cassandra-journal"]
       |      plugin = "cassandra-journal"
       |    }
       |    snapshot-store {
       |      auto-start-journals = ["cassandra-snapshot-store"]
       |      plugin = "cassandra-snapshot-store"
       |    }
       |  }
       |  http.server.remote-address-header = on
       |}
     """.stripMargin))

  nodeConfig(zoneHostNode)(
    ConfigFactory
      .parseString(s"""
          |akka.cluster.roles = ["zone-host"]
        """.stripMargin))

  nodeConfig(clientRelayNode)(
    ConfigFactory
      .parseString("""
          |akka.cluster.roles = ["client-relay"]
        """.stripMargin))

}

class LiquidityServerSpecMultiJvmNode1 extends LiquidityServerSpec
class LiquidityServerSpecMultiJvmNode2 extends LiquidityServerSpec
class LiquidityServerSpecMultiJvmNode3 extends LiquidityServerSpec

sealed abstract class LiquidityServerSpec
    extends MultiNodeSpec(LiquidityServerSpecConfig, config => ActorSystem("liquidity", config))
    with MultiNodeSpecCallbacks
    with FreeSpecLike
    with BeforeAndAfterAll
    with Inside {

  import com.dhpcs.liquidity.server.LiquidityServerSpecConfig._

  private[this] implicit val mat: Materializer = ActorMaterializer()

  private[this] lazy val cassandraDirectory = Files.createTempDirectory("liquidity-server-spec-cassandra-data")

  private[this] val akkaHttpPort = TestKit.freePort()

  private[this] val server = new LiquidityServer(
    pingInterval = 3.seconds,
    httpInterface = "0.0.0.0",
    httpPort = akkaHttpPort,
    analyticsKeyspace = "liquidity_analytics"
  )

  private[this] val httpBinding = server.bindHttp()

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    runOn(cassandraNode)(
      CassandraLauncher.start(
        cassandraDirectory = cassandraDirectory.toFile,
        configResource = CassandraLauncher.DefaultTestConfigResource,
        clean = true,
        port = 9042
      )
    )
    multiNodeSpecBeforeAll()
  }

  override protected def afterAll(): Unit = {
    Await.result(httpBinding.flatMap(_.unbind())(ExecutionContext.global), Duration.Inf)
    multiNodeSpecAfterAll()
    runOn(cassandraNode) {
      CassandraLauncher.stop()
      TestKit.delete(cassandraDirectory)
    }
    super.afterAll()
  }

  override def verifySystemShutdown: Boolean = true

  override def initialParticipants: Int = roles.size

  "Each test process" - (
    "will wait for the others to be ready to start" in enterBarrier("start")
  )

  "The test nodes" - (
    "will form a cluster" in {
      val cluster            = Cluster(system)
      val zoneHostAddress    = node(zoneHostNode).address
      val clientRelayAddress = node(clientRelayNode).address
      runOn(zoneHostNode)(
        cluster.join(zoneHostAddress)
      )
      runOn(clientRelayNode)(
        cluster.join(zoneHostAddress)
      )
      runOn(zoneHostNode, clientRelayNode)(
        awaitCond(
          cluster.state.members.map(_.address) == Set(zoneHostAddress, clientRelayAddress) &&
            cluster.state.members.forall(_.status == Up)
        )
      )
      enterBarrier("cluster")
    }
  )

  runOn(clientRelayNode) {
    "The LiquidityServer Protobuf WebSocket API" - {
      "will send a PingCommand when left idle" in withProtobufWsTestProbes { (sub, _) =>
        sub.within(3.5.seconds)(
          assert(
            expectProtobufCommand(sub) === proto.ws.protocol.ClientMessage.Command.Command
              .PingCommand(com.google.protobuf.ByteString.EMPTY))
        ); ()
      }
      "will reply with a CreateZoneResponse when sending a CreateZoneCommand" in withProtobufWsTestProbes {
        (sub, pub) =>
          val correlationId = 0L
          sendProtobufCreateZoneCommand(pub)(
            CreateZoneCommand(
              equityOwnerPublicKey = PublicKey(TestKit.rsaPublicKey.getEncoded),
              equityOwnerName = Some("Dave"),
              equityOwnerMetadata = None,
              equityAccountName = None,
              equityAccountMetadata = None,
              name = Some("Dave's Game")
            ),
            correlationId
          )
          inside(expectProtobufZoneResponse(sub, correlationId)) {
            case CreateZoneResponse(Validated.Valid(zone)) =>
              assert(zone.equityAccountId === AccountId("0"))
              assert(
                zone.members(MemberId("0")) === Member(MemberId("0"),
                                                       Set(PublicKey(TestKit.rsaPublicKey.getEncoded)),
                                                       name = Some("Dave")))
              assert(zone.accounts(AccountId("0")) === Account(AccountId("0"), ownerMemberIds = Set(MemberId("0"))))
              assert(zone.created === Spread(pivot = Instant.now().toEpochMilli, tolerance = 1000L))
              assert(
                zone.expires === Spread(pivot = Instant.now().plus(7, ChronoUnit.DAYS).toEpochMilli, tolerance = 1000L))
              assert(zone.transactions === Map.empty)
              assert(zone.name === Some("Dave's Game"))
              assert(zone.metadata === None)
          }; ()
      }
      "will reply with a JoinZoneResponse when sending a JoinZoneCommand" in withProtobufWsTestProbes { (sub, pub) =>
        val correlationId = 0L
        sendProtobufCreateZoneCommand(pub)(
          CreateZoneCommand(
            equityOwnerPublicKey = PublicKey(TestKit.rsaPublicKey.getEncoded),
            equityOwnerName = Some("Dave"),
            equityOwnerMetadata = None,
            equityAccountName = None,
            equityAccountMetadata = None,
            name = Some("Dave's Game")
          ),
          correlationId
        )
        inside(expectProtobufZoneResponse(sub, correlationId)) {
          case CreateZoneResponse(Validated.Valid(zone)) =>
            assert(zone.equityAccountId === AccountId("0"))
            assert(
              zone.members(MemberId("0")) === Member(MemberId("0"),
                                                     Set(PublicKey(TestKit.rsaPublicKey.getEncoded)),
                                                     name = Some("Dave")))
            assert(zone.accounts(AccountId("0")) === Account(AccountId("0"), ownerMemberIds = Set(MemberId("0"))))
            assert(zone.created === Spread(pivot = Instant.now().toEpochMilli, tolerance = 1000L))
            assert(
              zone.expires === Spread(pivot = Instant.now().plus(7, ChronoUnit.DAYS).toEpochMilli, tolerance = 1000L))
            assert(zone.transactions === Map.empty)
            assert(zone.name === Some("Dave's Game"))
            assert(zone.metadata === None)
            sendProtobufZoneCommand(pub)(
              zone.id,
              JoinZoneCommand,
              correlationId
            )
            inside(expectProtobufZoneResponse(sub, correlationId)) {
              case JoinZoneResponse(Validated.Valid(zoneAndConnectedClients)) =>
                val (_zone, _connectedClients) = zoneAndConnectedClients
                assert(_zone === zone)
                assert(_connectedClients.values.toSet === Set(PublicKey(TestKit.rsaPublicKey.getEncoded)))
            }
        }; ()
      }
    }
  }

  "Each test process" - (
    "will wait for the others to be ready to stop" in enterBarrier("stop")
  )

  private[this] def withProtobufWsTestProbes(
      test: (TestSubscriber.Probe[proto.ws.protocol.ClientMessage],
             TestPublisher.Probe[proto.ws.protocol.ServerMessage]) => Unit): Unit = {
    val testProbeFlow = Flow.fromSinkAndSourceMat(
      TestSink.probe[proto.ws.protocol.ClientMessage],
      TestSource.probe[proto.ws.protocol.ServerMessage]
    )(Keep.both)
    val wsClientFlow =
      ProtobufInFlow
        .viaMat(testProbeFlow)(Keep.right)
        .via(ProtobufOutFlow)
    val (_, (sub, pub)) = Http().singleWebSocketRequest(
      WebSocketRequest(s"ws://localhost:$akkaHttpPort/ws"),
      wsClientFlow
    )
    val keyOwnershipChallenge = inside(expectProtobufMessage(sub)) {
      case keyOwnershipChallenge: proto.ws.protocol.ClientMessage.Message.KeyOwnershipChallenge =>
        keyOwnershipChallenge.value
    }
    sendProtobufMessage(pub)(
      proto.ws.protocol.ServerMessage.Message
        .KeyOwnershipProof(
          Authentication.createKeyOwnershipProof(TestKit.rsaPublicKey, TestKit.rsaPrivateKey, keyOwnershipChallenge))
    )
    try test(sub, pub)
    finally {
      pub.sendComplete(); ()
    }
  }

  private final val ProtobufInFlow: Flow[WsMessage, proto.ws.protocol.ClientMessage, NotUsed] =
    Flow[WsMessage].flatMapConcat(wsMessage =>
      for (byteString <- wsMessage.asBinaryMessage match {
             case BinaryMessage.Streamed(dataStream) =>
               dataStream.fold(ByteString.empty)((acc, data) => acc ++ data)
             case BinaryMessage.Strict(data) => Source.single(data)
           }) yield proto.ws.protocol.ClientMessage.parseFrom(byteString.toArray))

  private final val ProtobufOutFlow: Flow[proto.ws.protocol.ServerMessage, WsMessage, NotUsed] =
    Flow[proto.ws.protocol.ServerMessage].map(
      serverMessage => BinaryMessage(ByteString(serverMessage.toByteArray))
    )

  private[this] def expectProtobufCommand(
      sub: TestSubscriber.Probe[proto.ws.protocol.ClientMessage]): proto.ws.protocol.ClientMessage.Command.Command =
    inside(sub.requestNext().message) {
      case proto.ws.protocol.ClientMessage.Message.Command(
          proto.ws.protocol.ClientMessage.Command(_, protoCommand)
          ) =>
        protoCommand
    }

  private[this] def sendProtobufCreateZoneCommand(pub: TestPublisher.Probe[proto.ws.protocol.ServerMessage])(
      createZoneCommand: CreateZoneCommand,
      correlationId: Long): Unit =
    sendProtobufMessage(pub)(
      proto.ws.protocol.ServerMessage.Message.Command(proto.ws.protocol.ServerMessage.Command(
        correlationId,
        proto.ws.protocol.ServerMessage.Command.Command.CreateZoneCommand(
          ProtoBinding[CreateZoneCommand, proto.ws.protocol.ZoneCommand.CreateZoneCommand, Any]
            .asProto(createZoneCommand)
        )
      )))

  private[this] def sendProtobufZoneCommand(pub: TestPublisher.Probe[proto.ws.protocol.ServerMessage])(
      zoneId: ZoneId,
      zoneCommand: ZoneCommand,
      correlationId: Long): Unit =
    sendProtobufMessage(pub)(
      proto.ws.protocol.ServerMessage.Message.Command(proto.ws.protocol.ServerMessage.Command(
        correlationId,
        proto.ws.protocol.ServerMessage.Command.Command.ZoneCommandEnvelope(
          proto.ws.protocol.ServerMessage.Command.ZoneCommandEnvelope(
            zoneId.id.toString,
            Some(
              ProtoBinding[ZoneCommand, proto.ws.protocol.ZoneCommand, Any].asProto(zoneCommand)
            )))
      )))

  private[this] def sendProtobufMessage(pub: TestPublisher.Probe[proto.ws.protocol.ServerMessage])(
      message: proto.ws.protocol.ServerMessage.Message): Unit = {
    pub.sendNext(
      proto.ws.protocol.ServerMessage(message)
    ); ()
  }

  private[this] def expectProtobufZoneResponse(sub: TestSubscriber.Probe[proto.ws.protocol.ClientMessage],
                                               expectedCorrelationId: Long): ZoneResponse =
    inside(expectProtobufMessage(sub)) {
      case proto.ws.protocol.ClientMessage.Message.Response(protoResponse) =>
        assert(protoResponse.correlationId == expectedCorrelationId)
        inside(protoResponse.response) {
          case proto.ws.protocol.ClientMessage.Response.Response.ZoneResponse(protoZoneResponse) =>
            ProtoBinding[ZoneResponse, proto.ws.protocol.ZoneResponse.ZoneResponse, Any]
              .asScala(protoZoneResponse.zoneResponse)(())
        }
    }

  private[this] def expectProtobufMessage(
      sub: TestSubscriber.Probe[proto.ws.protocol.ClientMessage]): proto.ws.protocol.ClientMessage.Message =
    sub.requestNext().message

}
