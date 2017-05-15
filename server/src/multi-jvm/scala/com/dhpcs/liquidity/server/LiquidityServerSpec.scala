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
       |    enable-additional-serialization-bindings = on
       |    allow-java-serialization = on
       |    serialize-messages = on
       |    serialize-creators = off
       |  }
       |  cluster {
       |    metrics.enabled = off
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
       |cassandra-journal {
       |  contact-points = ["localhost"]
       |  keyspace = "liquidity_server_v3"
       |}
       |liquidity {
       |  server.http {
       |    keep-alive-interval = 3s
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
         |liquidity.server.http.port = "$akkaHttpPort"
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
          sendJsonRpc(pub)(
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
          sendJsonRpc(pub)(
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
          sendJsonRpc(pub)(
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
        "will reply with a Protobuf CreateZoneResponse when sending a Protobuf CreateZoneCommand" in withProtobufWsTestProbes {
          (sub, pub) =>
            val correlationId = 0L
            sendProtobuf(pub)(
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
            sendProtobuf(pub)(
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
            sendProtobuf(pub)(
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
      test: (TestSubscriber.Probe[LegacyClientConnectionActor.WrappedJsonRpcResponseOrNotification],
             TestPublisher.Probe[LegacyClientConnectionActor.WrappedJsonRpcCommand]) => Unit): Unit = {
    val testProbeFlow = Flow.fromSinkAndSourceMat(
      TestSink.probe[LegacyClientConnectionActor.WrappedJsonRpcResponseOrNotification],
      TestSource.probe[LegacyClientConnectionActor.WrappedJsonRpcCommand]
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
      test: (TestSubscriber.Probe[ClientConnectionActor.WrappedProtobufResponseOrNotification],
             TestPublisher.Probe[ClientConnectionActor.WrappedProtobufCommand]) => Unit): Unit = {
    val testProbeFlow = Flow.fromSinkAndSourceMat(
      TestSink.probe[ClientConnectionActor.WrappedProtobufResponseOrNotification],
      TestSource.probe[ClientConnectionActor.WrappedProtobufCommand]
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
    : Flow[WsMessage, LegacyClientConnectionActor.WrappedJsonRpcResponseOrNotification, NotUsed] =
    Flow[WsMessage]
      .flatMapConcat {
        case binaryMessage: BinaryMessage =>
          sys.error(s"Received binary message: $binaryMessage")
        case textMessage: TextMessage =>
          for (jsonString <- textMessage match {
                 case TextMessage.Streamed(textStream) => textStream.fold("")(_ + _)
                 case TextMessage.Strict(text)         => Source.single(text)
               })
            yield LegacyClientConnectionActor.WrappedJsonRpcRequest(Json.parse(jsonString).as[JsonRpcRequestMessage])
          for (jsonString <- textMessage match {
                 case TextMessage.Streamed(textStream) => textStream.fold("")(_ + _)
                 case TextMessage.Strict(text)         => Source.single(text)
               })
            yield
              Json.parse(jsonString).as[JsonRpcMessage] match {
                case _: JsonRpcRequestMessage       => sys.error("Received JsonRpcRequestMessage")
                case _: JsonRpcRequestMessageBatch  => sys.error("Received JsonRpcRequestMessageBatch")
                case _: JsonRpcResponseMessageBatch => sys.error("Received JsonRpcResponseMessageBatch")
                case jsonRpcResponseMessage: JsonRpcResponseMessage =>
                  LegacyClientConnectionActor.WrappedJsonRpcResponse(jsonRpcResponseMessage)
                case jsonRpcNotificationMessage: JsonRpcNotificationMessage =>
                  LegacyClientConnectionActor.WrappedJsonRpcNotification(jsonRpcNotificationMessage)
              }
      }

  private final val JsonRpcOutFlow: Flow[LegacyClientConnectionActor.WrappedJsonRpcCommand, WsMessage, NotUsed] =
    Flow[LegacyClientConnectionActor.WrappedJsonRpcCommand].map {
      case LegacyClientConnectionActor.WrappedJsonRpcRequest(jsonRpcRequestMessage) =>
        TextMessage(Json.stringify(Json.toJson(jsonRpcRequestMessage)))
    }

  private final val ProtobufInFlow
    : Flow[WsMessage, ClientConnectionActor.WrappedProtobufResponseOrNotification, NotUsed] =
    Flow[WsMessage]
      .flatMapConcat {
        case binaryMessage: BinaryMessage =>
          for (byteString <- binaryMessage match {
                 case BinaryMessage.Streamed(dataStream) =>
                   dataStream.fold(ByteString.empty)((acc, data) => acc ++ data)
                 case BinaryMessage.Strict(data) => Source.single(data)
               })
            yield
              proto.ws.protocol.ResponseOrNotification
                .parseFrom(byteString.toArray)
                .responseOrNotification match {
                case proto.ws.protocol.ResponseOrNotification.ResponseOrNotification.Empty =>
                  sys.error("Empty")
                case proto.ws.protocol.ResponseOrNotification.ResponseOrNotification.Response(protoResponse) =>
                  ClientConnectionActor.WrappedProtobufResponse(protoResponse)
                case proto.ws.protocol.ResponseOrNotification.ResponseOrNotification.Notification(protoNotification) =>
                  ClientConnectionActor.WrappedProtobufNotification(protoNotification)
              }
        case textMessage: TextMessage =>
          sys.error(s"Received text message: $textMessage")
      }

  private final val ProtobufOutFlow: Flow[ClientConnectionActor.WrappedProtobufCommand, WsMessage, NotUsed] =
    Flow[ClientConnectionActor.WrappedProtobufCommand].map {
      case ClientConnectionActor.WrappedProtobufCommand(protobufCommand) =>
        BinaryMessage(ByteString(protobufCommand.toByteArray))
    }

  private[this] def expectJsonRpcNotification(
      sub: TestSubscriber.Probe[LegacyClientConnectionActor.WrappedJsonRpcResponseOrNotification])
    : protocol.legacy.Notification = {
    sub.request(1)
    sub.expectNextPF {
      case LegacyClientConnectionActor.WrappedJsonRpcNotification(jsonRpcNotificationMessage) =>
        val notification = protocol.legacy.Notification.read(jsonRpcNotificationMessage)
        notification.asOpt.value
    }
  }

  private[this] def sendJsonRpc(pub: TestPublisher.Probe[LegacyClientConnectionActor.WrappedJsonRpcCommand])(
      command: protocol.legacy.Command,
      correlationId: Long): Unit =
    pub.sendNext(
      LegacyClientConnectionActor.WrappedJsonRpcRequest(
        protocol.legacy.Command.write(command, id = NumericCorrelationId(correlationId))))

  private[this] def expectJsonRpcResponse(
      sub: TestSubscriber.Probe[LegacyClientConnectionActor.WrappedJsonRpcResponseOrNotification],
      expectedCorrelationId: Long,
      method: String): protocol.legacy.Response = {
    sub.request(1)
    sub.expectNextPF {
      case LegacyClientConnectionActor.WrappedJsonRpcResponse(
          jsonRpcResponseSuccessMessage: JsonRpcResponseSuccessMessage) =>
        assert(jsonRpcResponseSuccessMessage.id === NumericCorrelationId(expectedCorrelationId))
        val successResponse = protocol.legacy.SuccessResponse.read(jsonRpcResponseSuccessMessage, method)
        successResponse.asOpt.value
    }
  }

  // TODO: DRY
  private[this] def sendProtobuf(pub: TestPublisher.Probe[ClientConnectionActor.WrappedProtobufCommand])(
      command: protocol.legacy.Command,
      correlationId: Long): Unit =
    pub.sendNext(
      ClientConnectionActor.WrappedProtobufCommand(
        proto.ws.protocol.Command(
          correlationId,
          command match {
            case zoneCommand: protocol.legacy.ZoneCommand =>
              proto.ws.protocol.Command.Command.ZoneCommand(
                proto.ws.protocol.ZoneCommand(
                  ProtoConverter[protocol.legacy.ZoneCommand, proto.ws.protocol.ZoneCommand.ZoneCommand]
                    .asProto(zoneCommand))
              )
          }
        )
      )
    )

  // TODO: DRY
  private[this] def expectProtobufResponse(
      sub: TestSubscriber.Probe[ClientConnectionActor.WrappedProtobufResponseOrNotification],
      expectedCorrelationId: Long): protocol.Response = {
    sub.request(1)
    sub.expectNextPF {
      case ClientConnectionActor.WrappedProtobufResponse(protobufResponse) =>
        assert(protobufResponse.correlationId == expectedCorrelationId)
        protobufResponse.response match {
          case proto.ws.protocol.ResponseOrNotification.Response.Response.Empty =>
            sys.error("Empty")
          case proto.ws.protocol.ResponseOrNotification.Response.Response.ZoneResponse(protoZoneResponse) =>
            ProtoConverter[protocol.ZoneResponse, proto.ws.protocol.ZoneResponse.ZoneResponse]
              .asScala(protoZoneResponse.zoneResponse)
        }
    }
  }
}
