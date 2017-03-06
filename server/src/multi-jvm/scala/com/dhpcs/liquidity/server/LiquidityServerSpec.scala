package com.dhpcs.liquidity.server

import java.security.cert.{CertificateException, X509Certificate}
import javax.net.ssl.{SSLContext, X509TrustManager}

import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.cluster.MemberStatus.Up
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.http.scaladsl.model.ws.WebSocketRequest
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.cassandra.testkit.CassandraLauncher
import akka.persistence.query.PersistenceQuery
import akka.remote.testconductor.RoleName
import akka.remote.testkit.{MultiNodeConfig, MultiNodeSpec, MultiNodeSpecCallbacks}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Keep}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import akka.stream.testkit.{TestPublisher, TestSubscriber}
import com.dhpcs.jsonrpc.JsonRpcMessage.NoCorrelationId
import com.dhpcs.jsonrpc.{JsonRpcMessage, _}
import com.dhpcs.liquidity.certgen.CertGen
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.server.actor.ClientConnectionActor.WrappedJsonRpcMessage
import com.dhpcs.liquidity.server.actor.{ClientConnectionActor, ZoneValidatorActor}
import com.dhpcs.liquidity.ws.protocol._
import com.typesafe.config.ConfigFactory
import okio.ByteString
import org.apache.cassandra.io.util.FileUtils
import org.scalatest.EitherValues._
import org.scalatest.OptionValues._
import org.scalatest._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

object LiquidityServerSpecConfig extends MultiNodeConfig {

  val cassandraNode: RoleName   = role("cassandra-node")
  val zoneHostNode: RoleName    = role("zone-host")
  val clientRelayNode: RoleName = role("client-relay")

  commonConfig(
    ConfigFactory
      .parseString(s"""
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
          |    serialize-creators = on
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
    with WordSpecLike
    with BeforeAndAfterAll
    with Inside
    with Matchers {

  import com.dhpcs.liquidity.server.LiquidityServerSpecConfig._

  private[this] implicit val mat = ActorMaterializer()

  private[this] val cassandraDirectory = FileUtils.createTempFile("liquidity-cassandra-data", null)

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
            throw new CertificateException(s"Unknown public key: ${ByteString.of(publicKey.getEncoded: _*).base64}}")
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
        cassandraDirectory = cassandraDirectory,
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
    runOn(cassandraNode)(
      CassandraLauncher.stop()
    )
    FileUtils.deleteRecursive(cassandraDirectory)
    super.afterAll()
  }

  override def verifySystemShutdown: Boolean = true

  override def initialParticipants: Int = roles.size

  "Each test process" should (
    "wait for the others to be ready to start" in enterBarrier("start")
  )

  "The test nodes" should (
    "form a cluster" in {
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
    "The LiquidityServer WebSocket API" should {
      "send a SupportedVersionsNotification when connected" in withWsTestProbes { (sub, _) =>
        expectNotification(sub) shouldBe SupportedVersionsNotification(CompatibleVersionNumbers)
      }
      "send a KeepAliveNotification when left idle" in withWsTestProbes { (sub, _) =>
        expectNotification(sub) shouldBe SupportedVersionsNotification(CompatibleVersionNumbers)
        sub.within(3.5.seconds)(
          expectNotification(sub) shouldBe KeepAliveNotification
        )
      }
      "reply with a CreateZoneResponse when sending a CreateZoneCommand" in withWsTestProbes {
        (sub, pub) =>
          expectNotification(sub) shouldBe SupportedVersionsNotification(CompatibleVersionNumbers)
          send(pub)(
            CreateZoneCommand(
              equityOwnerPublicKey = clientPublicKey,
              equityOwnerName = Some("Dave"),
              equityOwnerMetadata = None,
              equityAccountName = None,
              equityAccountMetadata = None,
              name = Some("Dave's Game")
            )
          )
          inside(expectResponse(sub, "createZone")) {
            case CreateZoneResponse(zone) =>
              zone.equityAccountId shouldBe AccountId(0)
              zone.members(MemberId(0)) shouldBe Member(MemberId(0), clientPublicKey, name = Some("Dave"))
              zone.accounts(AccountId(0)) shouldBe Account(AccountId(0), ownerMemberIds = Set(MemberId(0)))
              zone.created should be > 0L
              zone.expires should be > zone.created
              zone.transactions shouldBe Map.empty
              zone.name shouldBe Some("Dave's Game")
              zone.metadata shouldBe None
          }
      }
      "reply with a JoinZoneResponse when sending a JoinZoneCommand" in withWsTestProbes {
        (sub, pub) =>
          expectNotification(sub) shouldBe SupportedVersionsNotification(CompatibleVersionNumbers)
          send(pub)(
            CreateZoneCommand(
              equityOwnerPublicKey = clientPublicKey,
              equityOwnerName = Some("Dave"),
              equityOwnerMetadata = None,
              equityAccountName = None,
              equityAccountMetadata = None,
              name = Some("Dave's Game")
            )
          )
          val zone = inside(expectResponse(sub, "createZone")) {
            case createZoneResponse: CreateZoneResponse =>
              val zone = createZoneResponse.zone
              zone.equityAccountId shouldBe AccountId(0)
              zone.members(MemberId(0)) shouldBe Member(MemberId(0), clientPublicKey, name = Some("Dave"))
              zone.accounts(AccountId(0)) shouldBe Account(AccountId(0), ownerMemberIds = Set(MemberId(0)))
              zone.created should be > 0L
              zone.expires should be > zone.created
              zone.transactions shouldBe Map.empty
              zone.name shouldBe Some("Dave's Game")
              zone.metadata shouldBe None
              zone
          }
          send(pub)(
            JoinZoneCommand(zone.id)
          )
          inside(expectResponse(sub, "joinZone")) {
            case joinZoneResponse: JoinZoneResponse =>
              joinZoneResponse.zone shouldBe zone
              joinZoneResponse.connectedClients shouldBe Set(clientPublicKey)
          }
      }
    }
  )

  "Each test process" should (
    "wait for the others to be ready to stop" in enterBarrier("stop")
  )

  private[this] def withWsTestProbes(
      test: (TestSubscriber.Probe[JsonRpcMessage], TestPublisher.Probe[JsonRpcMessage]) => Unit): Unit = {
    val testProbeFlow = Flow.fromSinkAndSourceMat(
      TestSink.probe[JsonRpcMessage],
      TestSource.probe[JsonRpcMessage]
    )(Keep.both)
    val wsClientFlow =
      ClientConnectionActor.WsMessageToWrappedJsonRpcMessageFlow
        .map(_.jsonRpcMessage)
        .viaMat(testProbeFlow)(Keep.right)
        .map(WrappedJsonRpcMessage)
        .via(ClientConnectionActor.WrappedJsonRpcMessageToWsMessageFlow)
    val (_, (sub, pub)) = Http().singleWebSocketRequest(
      WebSocketRequest(s"wss://localhost:$akkaHttpPort/ws"),
      wsClientFlow,
      clientHttpsConnectionContext
    )
    try test(sub, pub)
    finally pub.sendComplete()
  }

  private[this] def send(pub: TestPublisher.Probe[JsonRpcMessage])(command: Command): Unit =
    pub.sendNext(Command.write(command, id = NoCorrelationId))

  private[this] def expectNotification(sub: TestSubscriber.Probe[JsonRpcMessage]): Notification = {
    sub.request(1)
    sub.expectNextPF {
      case jsonRpcNotificationMessage: JsonRpcNotificationMessage =>
        val notification = Notification.read(jsonRpcNotificationMessage)
        notification.asOpt.value
    }
  }

  private[this] def expectResponse(sub: TestSubscriber.Probe[JsonRpcMessage], method: String): ResultResponse = {
    sub.request(1)
    sub.expectNextPF {
      case jsonRpcResponseMessage: JsonRpcResponseMessage =>
        val response = Response.read(jsonRpcResponseMessage, method)
        response.asOpt.value.right.value
    }
  }
}
