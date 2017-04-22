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
import akka.http.scaladsl.model.ws.{TextMessage, WebSocketRequest, Message => WsMessage}
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
import com.dhpcs.jsonrpc.JsonRpcMessage.NumericCorrelationId
import com.dhpcs.jsonrpc.{JsonRpcMessage, _}
import com.dhpcs.liquidity.certgen.CertGen
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.server.actor.ClientConnectionActor._
import com.dhpcs.liquidity.server.actor.ZoneValidatorActor
import com.dhpcs.liquidity.ws.protocol._
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
       |cassandra-journal.contact-points = ["localhost"]
       |liquidity.server.http {
       |  keep-alive-interval = 3s
       |  interface = "0.0.0.0"
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
    "The LiquidityServer WebSocket API" - {
      "will send a JSON-RPC SupportedVersionsNotification when connected" in withWsTestProbes { (sub, _) =>
        assert(expectJsonRpcNotification(sub) === SupportedVersionsNotification(CompatibleVersionNumbers))
      }
      "will send a JSON-RPC KeepAliveNotification when left idle" in withWsTestProbes { (sub, _) =>
        assert(expectJsonRpcNotification(sub) === SupportedVersionsNotification(CompatibleVersionNumbers))
        sub.within(3.5.seconds)(
          assert(expectJsonRpcNotification(sub) === KeepAliveNotification)
        )
      }
      "will reply with a JSON-RPC CreateZoneResponse when sending a JSON-RPC CreateZoneCommand" in withWsTestProbes {
        (sub, pub) =>
          assert(expectJsonRpcNotification(sub) === SupportedVersionsNotification(CompatibleVersionNumbers))
          val correlationId = 0L
          sendJsonRpc(pub)(
            CreateZoneCommand(
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
            case CreateZoneResponse(zone) =>
              assert(zone.equityAccountId === AccountId(0))
              assert(zone.members(MemberId(0)) === Member(MemberId(0), clientPublicKey, name = Some("Dave")))
              assert(zone.accounts(AccountId(0)) === Account(AccountId(0), ownerMemberIds = Set(MemberId(0))))
              assert(zone.created === Spread(pivot = Instant.now().toEpochMilli, tolerance = 250L))
              assert(
                zone.expires === Spread(pivot = Instant.now().plus(2, ChronoUnit.DAYS).toEpochMilli, tolerance = 250L))
              assert(zone.transactions === Map.empty)
              assert(zone.name === Some("Dave's Game"))
              assert(zone.metadata === None)
          }
      }
      "will reply with a JSON-RPC JoinZoneResponse when sending a JSON-RPC JoinZoneCommand" in withWsTestProbes {
        (sub, pub) =>
          assert(expectJsonRpcNotification(sub) === SupportedVersionsNotification(CompatibleVersionNumbers))
          val correlationId = 0L
          sendJsonRpc(pub)(
            CreateZoneCommand(
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
            case createZoneResponse: CreateZoneResponse =>
              val zone = createZoneResponse.zone
              assert(zone.equityAccountId === AccountId(0))
              assert(zone.members(MemberId(0)) === Member(MemberId(0), clientPublicKey, name = Some("Dave")))
              assert(zone.accounts(AccountId(0)) === Account(AccountId(0), ownerMemberIds = Set(MemberId(0))))
              assert(zone.created === Spread(pivot = Instant.now().toEpochMilli, tolerance = 250L))
              assert(
                zone.expires === Spread(pivot = Instant.now().plus(2, ChronoUnit.DAYS).toEpochMilli, tolerance = 250L))
              assert(zone.transactions === Map.empty)
              assert(zone.name === Some("Dave's Game"))
              assert(zone.metadata === None)
              zone
          }
          sendJsonRpc(pub)(
            JoinZoneCommand(zone.id),
            correlationId
          )
          inside(expectJsonRpcResponse(sub, correlationId, "joinZone")) {
            case joinZoneResponse: JoinZoneResponse =>
              assert(joinZoneResponse.zone === zone)
              assert(joinZoneResponse.connectedClients === Set(clientPublicKey))
          }
      }
    }
  )

  "Each test process" - (
    "will wait for the others to be ready to stop" in enterBarrier("stop")
  )

  private[this] def withWsTestProbes(
      test: (TestSubscriber.Probe[WrappedJsonRpcOrProtobufResponseOrNotification],
             TestPublisher.Probe[WrappedJsonRpcOrProtobufCommand]) => Unit): Unit = {
    val testProbeFlow = Flow.fromSinkAndSourceMat(
      TestSink.probe[WrappedJsonRpcOrProtobufResponseOrNotification],
      TestSource.probe[WrappedJsonRpcOrProtobufCommand]
    )(Keep.both)
    val wsClientFlow =
      InFlow
        .viaMat(testProbeFlow)(Keep.right)
        .via(OutFlow)
    val (_, (sub, pub)) = Http().singleWebSocketRequest(
      WebSocketRequest(s"wss://localhost:$akkaHttpPort/ws"),
      wsClientFlow,
      clientHttpsConnectionContext
    )
    try test(sub, pub)
    finally pub.sendComplete()
  }

  private final val InFlow: Flow[WsMessage, WrappedJsonRpcOrProtobufResponseOrNotification, NotUsed] =
    Flow[WsMessage]
      .flatMapConcat(
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
                  WrappedJsonRpcResponse(jsonRpcResponseMessage)
                case jsonRpcNotificationMessage: JsonRpcNotificationMessage =>
                  WrappedJsonRpcNotification(jsonRpcNotificationMessage)
            })

  private final val OutFlow: Flow[WrappedJsonRpcOrProtobufCommand, WsMessage, NotUsed] =
    Flow[WrappedJsonRpcOrProtobufCommand].map {
      case WrappedJsonRpcRequest(jsonRpcRequestMessage) =>
        TextMessage(Json.stringify(Json.toJson(jsonRpcRequestMessage)))
    }

  private[this] def expectJsonRpcNotification(
      sub: TestSubscriber.Probe[WrappedJsonRpcOrProtobufResponseOrNotification]): Notification = {
    sub.request(1)
    sub.expectNextPF {
      case WrappedJsonRpcNotification(jsonRpcNotificationMessage) =>
        val notification = Notification.read(jsonRpcNotificationMessage)
        notification.asOpt.value
    }
  }

  private[this] def sendJsonRpc(pub: TestPublisher.Probe[WrappedJsonRpcOrProtobufCommand])(command: Command,
                                                                                           correlationId: Long): Unit =
    pub.sendNext(WrappedJsonRpcRequest(Command.write(command, id = NumericCorrelationId(correlationId))))

  private[this] def expectJsonRpcResponse(sub: TestSubscriber.Probe[WrappedJsonRpcOrProtobufResponseOrNotification],
                                          expectedCorrelationId: Long,
                                          method: String): Response = {
    sub.request(1)
    sub.expectNextPF {
      case WrappedJsonRpcResponse(jsonRpcResponseSuccessMessage: JsonRpcResponseSuccessMessage) =>
        assert(jsonRpcResponseSuccessMessage.id === NumericCorrelationId(expectedCorrelationId))
        val successResponse = SuccessResponse.read(jsonRpcResponseSuccessMessage, method)
        successResponse.asOpt.value
    }
  }
}
