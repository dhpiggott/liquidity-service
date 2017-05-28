package com.dhpcs.liquidity.server

import java.nio.file.Files
import java.security.cert.{CertificateException, X509Certificate}
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.net.ssl.{SSLContext, X509TrustManager}

import akka.NotUsed
import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.cluster.MemberStatus.Up
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.http.scaladsl.model.ws.{BinaryMessage, TextMessage, WebSocketRequest, Message => WsMessage}
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.cassandra.testkit.CassandraLauncher
import akka.persistence.query.PersistenceQuery
import akka.remote.testconductor.RoleName
import akka.remote.testkit.{MultiNodeConfig, MultiNodeSpec, MultiNodeSpecCallbacks}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Keep, Source}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import akka.stream.testkit.{TestPublisher, TestSubscriber}
import akka.util.ByteString
import com.dhpcs.jsonrpc.JsonRpcMessage.NumericCorrelationId
import com.dhpcs.jsonrpc._
import com.dhpcs.liquidity.certgen.CertGen
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.serialization.ProtoConverter
import com.dhpcs.liquidity.server.actor._
import com.dhpcs.liquidity.ws.protocol
import com.dhpcs.liquidity.ws.protocol.ZoneResponse
import com.typesafe.config.ConfigFactory
import org.scalactic.TripleEqualsSupport.Spread
import org.scalatest.OptionValues._
import org.scalatest._
import play.api.libs.json.Json

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

object LiquidityServerSpecConfig extends MultiNodeConfig {

  val cassandraNode: RoleName   = role("cassandra-node")
  val zoneHostNode: RoleName    = role("zone-host")
  val clientRelayNode: RoleName = role("client-relay")

  private val clusterHttpManagementPort = freePort()

  commonConfig(ConfigFactory.parseString(s"""
       |akka {
       |  actor {
       |    provider = "akka.cluster.ClusterActorRefProvider"
       |    serializers {
       |      zone-event = "com.dhpcs.liquidity.server.serialization.ZoneEventSerializer"
       |      client-connection-protocol = "com.dhpcs.liquidity.server.serialization.ClientConnectionMessageSerializer"
       |      zone-validator-protocol = "com.dhpcs.liquidity.server.serialization.ZoneValidatorMessageSerializer"
       |    }
       |    serialization-bindings {
       |      "com.dhpcs.liquidity.persistence.ZoneEvent" = zone-event
       |      "com.dhpcs.liquidity.actor.protocol.ClientConnectionMessage" = client-connection-protocol
       |      "com.dhpcs.liquidity.actor.protocol.ZoneValidatorMessage" = zone-validator-protocol
       |    }
       |    allow-java-serialization = on
       |    serialize-messages = on
       |    serialize-creators = off
       |  }
       |  cluster {
       |    http.management.port = $clusterHttpManagementPort
       |    metrics.enabled = off
       |  }
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
       |cassandra-journal {
       |  contact-points = ["localhost"]
       |  keyspace = "liquidity_server_v3"
       |}
       |liquidity {
       |  server.http {
       |    ping-interval = 3s
       |    interface = "0.0.0.0"
       |  }
       |  analytics.cassandra.keyspace = "liquidity_analytics_v3"
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

  private[this] implicit val mat = ActorMaterializer()

  private[this] lazy val cassandraDirectory = Files.createTempDirectory("liquidity-server-spec-cassandra-data")

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

  private[this] val akkaHttpPort = freePort()

  private[this] val (serverCertificate, serverKeyManagers) = {
    val (certificate, privateKey) = CertGen.generateCertKey(subjectAlternativeName = Some("localhost"))
    (certificate, createKeyManagers(certificate, privateKey))
  }

  private[this] val server = new LiquidityServer(
    ConfigFactory.parseString(s"""
         |liquidity.server.http.port = $akkaHttpPort
          """.stripMargin).withFallback(system.settings.config),
    readJournal,
    futureAnalyticsStore,
    zoneValidatorShardRegion,
    serverKeyManagers
  )

  private[this] val (clientPublicKey, clientHttpsConnectionContext) = {
    val (certificate, privateKey) = CertGen.generateCertKey(subjectAlternativeName = None)
    val publicKey                 = PublicKey(certificate.getPublicKey.getEncoded)
    val sslContext                = SSLContext.getInstance("TLS")
    sslContext.init(
      createKeyManagers(certificate, privateKey),
      Array(new X509TrustManager {

        override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit =
          throw new CertificateException

        override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = {
          val publicKey = chain(0).getPublicKey
          if (publicKey != serverCertificate.getPublicKey)
            throw new CertificateException(
              s"Unknown public key: ${okio.ByteString.of(publicKey.getEncoded: _*).base64}}")
        }

        override def getAcceptedIssuers: Array[X509Certificate] = Array.empty

      }),
      null
    )
    (publicKey, ConnectionContext.https(sslContext))
  }

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
    Await.result(server.shutdown(), Duration.Inf)
    multiNodeSpecAfterAll()
    runOn(cassandraNode) {
      CassandraLauncher.stop()
      delete(cassandraDirectory)
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

  runOn(clientRelayNode)(
    "The LiquidityServer JSON-RPC WebSocket API" - {
      "will send a JSON-RPC SupportedVersionsNotification when connected" in withJsonRpcWsTestProbes { (sub, _) =>
        sub.within(5.seconds)(
          assert(
            expectJsonRpcNotification(sub) === protocol.legacy.SupportedVersionsNotification(
              protocol.legacy.CompatibleVersionNumbers))
        )
      }
      "will send a JSON-RPC KeepAliveNotification when left idle" in withJsonRpcWsTestProbes { (sub, _) =>
        sub.within(5.seconds)(
          assert(
            expectJsonRpcNotification(sub) === protocol.legacy.SupportedVersionsNotification(
              protocol.legacy.CompatibleVersionNumbers))
        )
        sub.within(3.5.seconds)(
          assert(expectJsonRpcNotification(sub) === protocol.legacy.KeepAliveNotification)
        )
      }
      "will reply with a JSON-RPC CreateZoneResponse when sending a JSON-RPC CreateZoneCommand" in withJsonRpcWsTestProbes {
        (sub, pub) =>
          sub.within(5.seconds)(
            assert(
              expectJsonRpcNotification(sub) === protocol.legacy.SupportedVersionsNotification(
                protocol.legacy.CompatibleVersionNumbers))
          )
          val correlationId = 0L
          sendJsonRpcCommand(pub)(
            protocol.legacy.CreateZoneCommand(
              equityOwnerPublicKey = clientPublicKey,
              equityOwnerName = Some("Dave"),
              equityOwnerMetadata = None,
              equityAccountName = None,
              equityAccountMetadata = None,
              name = Some("Dave's Game")
            ),
            correlationId
          )
          inside(expectJsonRpcResponse(sub, correlationId, "createZone")) {
            case protocol.legacy.CreateZoneResponse(zone) =>
              assert(zone.equityAccountId === AccountId(0))
              assert(zone.members(MemberId(0)) === Member(MemberId(0), clientPublicKey, name = Some("Dave")))
              assert(zone.accounts(AccountId(0)) === Account(AccountId(0), ownerMemberIds = Set(MemberId(0))))
              assert(zone.created === Spread(pivot = Instant.now().toEpochMilli, tolerance = 500L))
              assert(
                zone.expires === Spread(pivot = Instant.now().plus(2, ChronoUnit.DAYS).toEpochMilli, tolerance = 500L))
              assert(zone.transactions === Map.empty)
              assert(zone.name === Some("Dave's Game"))
              assert(zone.metadata === None)
          }
      }
      "will reply with a JSON-RPC JoinZoneResponse when sending a JSON-RPC JoinZoneCommand" in withJsonRpcWsTestProbes {
        (sub, pub) =>
          sub.within(5.seconds)(
            assert(
              expectJsonRpcNotification(sub) === protocol.legacy.SupportedVersionsNotification(
                protocol.legacy.CompatibleVersionNumbers))
          )
          val correlationId = 0L
          sendJsonRpcCommand(pub)(
            protocol.legacy.CreateZoneCommand(
              equityOwnerPublicKey = clientPublicKey,
              equityOwnerName = Some("Dave"),
              equityOwnerMetadata = None,
              equityAccountName = None,
              equityAccountMetadata = None,
              name = Some("Dave's Game")
            ),
            correlationId
          )
          val zone = inside(expectJsonRpcResponse(sub, correlationId, "createZone")) {
            case createZoneResponse: protocol.legacy.CreateZoneResponse =>
              val zone = createZoneResponse.zone
              assert(zone.equityAccountId === AccountId(0))
              assert(zone.members(MemberId(0)) === Member(MemberId(0), clientPublicKey, name = Some("Dave")))
              assert(zone.accounts(AccountId(0)) === Account(AccountId(0), ownerMemberIds = Set(MemberId(0))))
              assert(zone.created === Spread(pivot = Instant.now().toEpochMilli, tolerance = 500L))
              assert(
                zone.expires === Spread(pivot = Instant.now().plus(2, ChronoUnit.DAYS).toEpochMilli, tolerance = 500L))
              assert(zone.transactions === Map.empty)
              assert(zone.name === Some("Dave's Game"))
              assert(zone.metadata === None)
              zone
          }
          sendJsonRpcCommand(pub)(
            protocol.legacy.JoinZoneCommand(zone.id),
            correlationId
          )
          inside(expectJsonRpcResponse(sub, correlationId, "joinZone")) {
            case joinZoneResponse: protocol.legacy.JoinZoneResponse =>
              assert(joinZoneResponse.zone === zone)
              assert(joinZoneResponse.connectedClients === Set(clientPublicKey))
          }
      }
      "The LiquidityServer Protobuf WebSocket API" - {
        "will send a Protobuf PingCommand when left idle" in withProtobufWsTestProbes { (sub, _) =>
          sub.within(3.5.seconds)(
            assert(expectProtobufCommand(sub) === protocol.PingCommand)
          )
        }
        "will reply with a Protobuf CreateZoneResponse when sending a Protobuf CreateZoneCommand" in withProtobufWsTestProbes {
          (sub, pub) =>
            val correlationId = 0L
            sendProtobufCommand(pub)(
              protocol.legacy.CreateZoneCommand(
                equityOwnerPublicKey = clientPublicKey,
                equityOwnerName = Some("Dave"),
                equityOwnerMetadata = None,
                equityAccountName = None,
                equityAccountMetadata = None,
                name = Some("Dave's Game")
              ),
              correlationId
            )
            inside(expectProtobufResponse(sub, correlationId)) {
              case protocol.CreateZoneResponse(zone) =>
                assert(zone.equityAccountId === AccountId(0))
                assert(zone.members(MemberId(0)) === Member(MemberId(0), clientPublicKey, name = Some("Dave")))
                assert(zone.accounts(AccountId(0)) === Account(AccountId(0), ownerMemberIds = Set(MemberId(0))))
                assert(zone.created === Spread(pivot = Instant.now().toEpochMilli, tolerance = 500L))
                assert(zone.expires === Spread(pivot = Instant.now().plus(2, ChronoUnit.DAYS).toEpochMilli,
                                               tolerance = 500L))
                assert(zone.transactions === Map.empty)
                assert(zone.name === Some("Dave's Game"))
                assert(zone.metadata === None)
            }
        }
        "will reply with a Protobuf JoinZoneResponse when sending a Protobuf JoinZoneCommand" in withProtobufWsTestProbes {
          (sub, pub) =>
            val correlationId = 0L
            sendProtobufCommand(pub)(
              protocol.legacy.CreateZoneCommand(
                equityOwnerPublicKey = clientPublicKey,
                equityOwnerName = Some("Dave"),
                equityOwnerMetadata = None,
                equityAccountName = None,
                equityAccountMetadata = None,
                name = Some("Dave's Game")
              ),
              correlationId
            )
            val zone = inside(expectProtobufResponse(sub, correlationId)) {
              case createZoneResponse: protocol.CreateZoneResponse =>
                val zone = createZoneResponse.zone
                assert(zone.equityAccountId === AccountId(0))
                assert(zone.members(MemberId(0)) === Member(MemberId(0), clientPublicKey, name = Some("Dave")))
                assert(zone.accounts(AccountId(0)) === Account(AccountId(0), ownerMemberIds = Set(MemberId(0))))
                assert(zone.created === Spread(pivot = Instant.now().toEpochMilli, tolerance = 500L))
                assert(zone.expires === Spread(pivot = Instant.now().plus(2, ChronoUnit.DAYS).toEpochMilli,
                                               tolerance = 500L))
                assert(zone.transactions === Map.empty)
                assert(zone.name === Some("Dave's Game"))
                assert(zone.metadata === None)
                zone
            }
            sendProtobufCommand(pub)(
              protocol.legacy.JoinZoneCommand(zone.id),
              correlationId
            )
            inside(expectProtobufResponse(sub, correlationId)) {
              case joinZoneResponse: protocol.JoinZoneResponse =>
                assert(joinZoneResponse.zone === zone)
                assert(joinZoneResponse.connectedClients === Set(clientPublicKey))
            }
        }
      }
    }
  )

  "Each test process" - (
    "will wait for the others to be ready to stop" in enterBarrier("stop")
  )

  private[this] def withJsonRpcWsTestProbes(
      test: (TestSubscriber.Probe[LegacyClientConnectionActor.WrappedResponseOrNotification],
             TestPublisher.Probe[LegacyClientConnectionActor.WrappedCommand]) => Unit): Unit = {
    val testProbeFlow = Flow.fromSinkAndSourceMat(
      TestSink.probe[LegacyClientConnectionActor.WrappedResponseOrNotification],
      TestSource.probe[LegacyClientConnectionActor.WrappedCommand]
    )(Keep.both)
    val wsClientFlow =
      JsonRpcInFlow
        .viaMat(testProbeFlow)(Keep.right)
        .via(JsonRpcOutFlow)
    val (_, (sub, pub)) = Http().singleWebSocketRequest(
      WebSocketRequest(s"wss://localhost:$akkaHttpPort/ws"),
      wsClientFlow,
      clientHttpsConnectionContext
    )
    try test(sub, pub)
    finally pub.sendComplete()
  }

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
      WebSocketRequest(s"wss://localhost:$akkaHttpPort/bws"),
      wsClientFlow,
      clientHttpsConnectionContext
    )
    try test(sub, pub)
    finally pub.sendComplete()
  }

  private final val JsonRpcInFlow
    : Flow[WsMessage, LegacyClientConnectionActor.WrappedResponseOrNotification, NotUsed] =
    Flow[WsMessage].flatMapConcat(
      wsMessage =>
        for (jsonString <- wsMessage.asTextMessage match {
               case TextMessage.Streamed(textStream) => textStream.fold("")(_ + _)
               case TextMessage.Strict(text)         => Source.single(text)
             })
          yield
            Json.parse(jsonString).as[JsonRpcMessage] match {
              case _: JsonRpcRequestMessage       => sys.error("Received JsonRpcRequestMessage")
              case _: JsonRpcRequestMessageBatch  => sys.error("Received JsonRpcRequestMessageBatch")
              case _: JsonRpcResponseMessageBatch => sys.error("Received JsonRpcResponseMessageBatch")
              case jsonRpcResponseMessage: JsonRpcResponseMessage =>
                LegacyClientConnectionActor.WrappedResponse(jsonRpcResponseMessage)
              case jsonRpcNotificationMessage: JsonRpcNotificationMessage =>
                LegacyClientConnectionActor.WrappedNotification(jsonRpcNotificationMessage)
          })

  private final val JsonRpcOutFlow: Flow[LegacyClientConnectionActor.WrappedCommand, WsMessage, NotUsed] =
    Flow[LegacyClientConnectionActor.WrappedCommand].map {
      case LegacyClientConnectionActor.WrappedCommand(jsonRpcRequestMessage) =>
        TextMessage(Json.stringify(Json.toJson(jsonRpcRequestMessage)))
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

  private[this] def expectJsonRpcNotification(
      sub: TestSubscriber.Probe[LegacyClientConnectionActor.WrappedResponseOrNotification])
    : protocol.legacy.Notification = {
    sub.request(1)
    sub.expectNextPF {
      case LegacyClientConnectionActor.WrappedNotification(jsonRpcNotificationMessage) =>
        protocol.legacy.Notification.read(jsonRpcNotificationMessage).asOpt.value
    }
  }

  private[this] def sendJsonRpcCommand(pub: TestPublisher.Probe[LegacyClientConnectionActor.WrappedCommand])(
      command: protocol.legacy.Command,
      correlationId: Long): Unit =
    pub.sendNext(
      LegacyClientConnectionActor.WrappedCommand(
        protocol.legacy.Command.write(command, id = NumericCorrelationId(correlationId))
      ))

  private[this] def expectJsonRpcResponse(
      sub: TestSubscriber.Probe[LegacyClientConnectionActor.WrappedResponseOrNotification],
      expectedCorrelationId: Long,
      method: String): protocol.legacy.Response = {
    sub.request(1)
    sub.expectNextPF {
      case LegacyClientConnectionActor.WrappedResponse(jsonRpcResponseSuccessMessage: JsonRpcResponseSuccessMessage) =>
        assert(jsonRpcResponseSuccessMessage.id === NumericCorrelationId(expectedCorrelationId))
        protocol.legacy.SuccessResponse.read(jsonRpcResponseSuccessMessage, method).asOpt.value
    }
  }

  private[this] def expectProtobufCommand(
      sub: TestSubscriber.Probe[proto.ws.protocol.ClientMessage]): protocol.ClientCommand =
    inside(sub.requestNext().commandOrResponseOrNotification) {
      case proto.ws.protocol.ClientMessage.CommandOrResponseOrNotification.Command(protoCommand) =>
        inside(protoCommand.command) {
          case proto.ws.protocol.ClientMessage.Command.Command.PingCommand(protoPingCommand) =>
            ProtoConverter[protocol.PingCommand.type, proto.ws.protocol.PingCommand]
              .asScala(protoPingCommand)
        }
    }

  private[this] def sendProtobufCommand(pub: TestPublisher.Probe[proto.ws.protocol.ServerMessage])(
      command: protocol.legacy.Command,
      correlationId: Long): Unit =
    pub.sendNext(
      proto.ws.protocol.ServerMessage(
        proto.ws.protocol.ServerMessage.CommandOrResponse.Command(proto.ws.protocol.ServerMessage.Command(
          correlationId,
          command match {
            case zoneCommand: protocol.legacy.ZoneCommand =>
              proto.ws.protocol.ServerMessage.Command.Command.ZoneCommand(
                proto.ws.protocol.ZoneCommand(
                  ProtoConverter[protocol.legacy.ZoneCommand, proto.ws.protocol.ZoneCommand.ZoneCommand]
                    .asProto(zoneCommand)
                ))
          }
        ))))

  private[this] def expectProtobufResponse(sub: TestSubscriber.Probe[proto.ws.protocol.ClientMessage],
                                           expectedCorrelationId: Long): protocol.ServerResponse =
    inside(sub.requestNext().commandOrResponseOrNotification) {
      case proto.ws.protocol.ClientMessage.CommandOrResponseOrNotification.Response(protoResponse) =>
        assert(protoResponse.correlationId == expectedCorrelationId)
        inside(protoResponse.response) {
          case proto.ws.protocol.ClientMessage.Response.Response.ZoneResponse(protoZoneResponse) =>
            ProtoConverter[ZoneResponse, proto.ws.protocol.ZoneResponse.ZoneResponse]
              .asScala(protoZoneResponse.zoneResponse)
        }
    }

}
