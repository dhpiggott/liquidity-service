package com.dhpcs.liquidity.server

import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.security.cert.{Certificate, CertificateException, X509Certificate}
import java.security.{KeyStore, PrivateKey}
import javax.net.ssl.{KeyManager, KeyManagerFactory, SSLContext, X509TrustManager}

import akka.actor.ActorSystem
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.cassandra.testkit.CassandraLauncher
import akka.persistence.query.PersistenceQuery
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Keep}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import akka.stream.testkit.{TestPublisher, TestSubscriber}
import akka.testkit.TestKit
import com.dhpcs.jsonrpc.{JsonRpcNotificationMessage, JsonRpcResponseMessage}
import com.dhpcs.liquidity.certgen.CertGen
import com.dhpcs.liquidity.model.{Member, MemberId, PublicKey}
import com.dhpcs.liquidity.protocol._
import com.dhpcs.liquidity.server.LiquidityServerSpec._
import com.dhpcs.liquidity.server.actors.ZoneValidatorActor
import com.typesafe.config.ConfigFactory
import okio.ByteString
import org.apache.cassandra.io.util.FileUtils
import org.scalatest.EitherValues._
import org.scalatest.OptionValues._
import org.scalatest.{BeforeAndAfterAll, Inside, Matchers, fixture}
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.Await
import scala.concurrent.duration._

object LiquidityServerSpec {

  private final val KeyStoreEntryAlias = "identity"
  private final val KeyStoreEntryPassword = Array.emptyCharArray

  private def createKeyManagers(certificate: Certificate,
                                privateKey: PrivateKey): Array[KeyManager] = {
    val keyStore = KeyStore.getInstance("PKCS12")
    keyStore.load(null, null)
    keyStore.setKeyEntry(
      KeyStoreEntryAlias,
      privateKey,
      KeyStoreEntryPassword,
      Array(certificate)
    )
    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keyManagerFactory.init(
      keyStore,
      Array.emptyCharArray
    )
    keyManagerFactory.getKeyManagers
  }
}

class LiquidityServerSpec extends fixture.WordSpec
  with Inside with Matchers with BeforeAndAfterAll {

  private[this] val akkaRemotingPort = freePort()
  private[this] val akkaHttpPort = freePort()

  private[this] def freePort(): Int = {
    val serverSocket = ServerSocketChannel.open().socket()
    serverSocket.bind(new InetSocketAddress("localhost", 0))
    val port = serverSocket.getLocalPort
    serverSocket.close()
    port
  }

  private[this] val cassandraDirectory = FileUtils.createTempFile("liquidity-cassandra-data", null)

  private[this] val config =
    ConfigFactory.parseString(
      s"""
         |akka {
         |  loggers = ["akka.event.slf4j.Slf4jLogger"]
         |  loglevel = "DEBUG"
         |  logging-filter = "akka.event.slf4j.Slf4jLoggingFilter"
         |  actor {
         |    provider = "akka.cluster.ClusterActorRefProvider"
         |    serializers.event = "com.dhpcs.liquidity.persistence.PlayJsonEventSerializer"
         |    serialization-bindings {
         |      "java.io.Serializable" = none
         |      "com.dhpcs.liquidity.persistence.Event" = event
         |    }
         |  }
         |  remote.netty.tcp {
         |    hostname = "localhost"
         |    port = $akkaRemotingPort
         |  }
         |  cluster {
         |    metrics.enabled = off
         |    seed-nodes = ["akka.tcp://liquidity@localhost:$akkaRemotingPort"]
         |    sharding.state-store-mode = ddata
         |  }
         |  extensions += "akka.cluster.ddata.DistributedData"
         |  persistence {
         |    journal.plugin = "cassandra-journal"
         |    snapshot-store.plugin = "cassandra-snapshot-store"
         |  }
         |  http.server {
         |    remote-address-header = on
         |    parsing.tls-session-info-header = on
         |  }
         |}
         |cassandra-journal.contact-points = ["localhost:${CassandraLauncher.randomPort}"]
         |cassandra-snapshot-store.contact-points = ["localhost:${CassandraLauncher.randomPort}"]
         |liquidity {
         |  http {
         |    keep-alive-interval = "3 seconds"
         |    interface = "0.0.0.0"
         |    port = "$akkaHttpPort"
         |  }
         |}
    """.stripMargin
    ).resolve()

  private[this] implicit val system = ActorSystem("liquidity", config)
  private[this] implicit val mat = ActorMaterializer()

  private[this] val readJournal = PersistenceQuery(system)
    .readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

  private[this] val zoneValidatorShardRegion = ClusterSharding(system).start(
    typeName = ZoneValidatorActor.ShardName,
    entityProps = ZoneValidatorActor.props,
    settings = ClusterShardingSettings(system),
    extractEntityId = ZoneValidatorActor.extractEntityId,
    extractShardId = ZoneValidatorActor.extractShardId
  )

  private[this] val (serverPublicKey, serverKeyManagers) = {
    val (certificate, privateKey) = CertGen.generateCertKey(subjectAlternativeName = Some("localhost"))
    (certificate.getPublicKey, createKeyManagers(certificate, privateKey))
  }

  private[this] val (clientHttpsConnectionContext, clientPublicKey) = {
    val (certificate, privateKey) = CertGen.generateCertKey(subjectAlternativeName = None)
    val keyManagers = createKeyManagers(certificate, privateKey)
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(
      keyManagers,
      Array(new X509TrustManager {
        @throws(classOf[CertificateException])
        override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit =
          checkTrusted(chain)

        @throws(classOf[CertificateException])
        override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit =
          checkTrusted(chain)

        @throws(classOf[CertificateException])
        private def checkTrusted(chain: Array[X509Certificate]): Unit = {
          val publicKey = chain(0).getPublicKey
          if (!publicKey.equals(serverPublicKey)) {
            throw new CertificateException(
              s"Unknown public key: ${ByteString.of(publicKey.getEncoded: _*).base64}"
            )
          }
        }

        override def getAcceptedIssuers: Array[X509Certificate] = Array.empty
      }),
      null
    )
    val publicKey = PublicKey(certificate.getPublicKey.getEncoded)
    (ConnectionContext.https(sslContext), publicKey)
  }


  override protected type FixtureParam = (TestSubscriber.Probe[Message], TestPublisher.Probe[Message])

  override protected def beforeAll(): Unit = {
    CassandraLauncher.start(
      cassandraDirectory = cassandraDirectory,
      configResource = CassandraLauncher.DefaultTestConfigResource,
      clean = true,
      port = 0
    )
    super.beforeAll()
  }

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    CassandraLauncher.stop()
    FileUtils.deleteRecursive(cassandraDirectory)
    super.afterAll()
  }

  override protected def withFixture(test: OneArgTest) = {
    val server = new LiquidityServer(
      config,
      readJournal,
      zoneValidatorShardRegion,
      serverKeyManagers
    )
    val flow = Flow.fromSinkAndSourceMat(
      TestSink.probe[Message],
      TestSource.probe[Message]
    )(Keep.both)
    val (_, (sub, pub)) = Http().singleWebSocketRequest(
      WebSocketRequest(s"wss://localhost:$akkaHttpPort/ws"),
      flow,
      clientHttpsConnectionContext
    )
    try withFixture(test.toNoArgTest((sub, pub)))
    finally {
      pub.sendComplete()
      Await.result(server.shutdown(), Duration.Inf)
    }
  }

  "The LiquidityServer WebSocket API" should {
    "send a SupportedVersionsNotification when connected" in { fixture =>
      val (sub, _) = fixture
      expectNotification(sub) shouldBe SupportedVersionsNotification(CompatibleVersionNumbers)
    }
    "send a KeepAliveNotification when left idle" in { fixture =>
      val (sub, _) = fixture
      expectNotification(sub) shouldBe SupportedVersionsNotification(CompatibleVersionNumbers)
      sub.within(3.5.seconds)(
        expectNotification(sub) shouldBe KeepAliveNotification
      )
    }
    "send a CreateZoneResponse after a CreateZoneCommand" in { fixture =>
      val (sub, pub) = fixture
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
      inside(expectResultResponse(sub, "createZone")) {
        case CreateZoneResponse(zone) =>
          zone.members(MemberId(0)) shouldBe Member(MemberId(0), clientPublicKey, name = Some("Dave"))
          zone.name shouldBe Some("Dave's Game")
      }
    }
    "send a JoinZoneResponse after a JoinZoneCommand" in { fixture =>
      val (sub, pub) = fixture
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
      val zoneId = inside(expectResultResponse(sub, "createZone")) {
        case CreateZoneResponse(zone) =>
          zone.members(MemberId(0)) shouldBe Member(MemberId(0), clientPublicKey, name = Some("Dave"))
          zone.name shouldBe Some("Dave's Game")
          zone.id
      }
      send(pub)(
        JoinZoneCommand(zoneId)
      )
      inside(expectResultResponse(sub, "joinZone")) {
        case JoinZoneResponse(_, connectedClients) => connectedClients shouldBe Set(clientPublicKey)
      }
    }
  }

  private[this] def send(pub: TestPublisher.Probe[Message])
                        (command: Command): Unit =
    pub.sendNext(
      TextMessage.Strict(Json.stringify(Json.toJson(
        Command.write(command, id = None)
      )))
    )

  private[this] def expectNotification(sub: TestSubscriber.Probe[Message]): Notification = {
    val jsValue = expectJsValue(sub)
    val jsonRpcNotificationMessage = jsValue.asOpt[JsonRpcNotificationMessage]
    val notification = Notification.read(jsonRpcNotificationMessage.value)
    notification.value.asOpt.value
  }

  private[this] def expectResultResponse[A](sub: TestSubscriber.Probe[Message], method: String): ResultResponse = {
    val jsValue = expectJsValue(sub)
    val jsonRpcResponseMessage = jsValue.asOpt[JsonRpcResponseMessage]
    val response = Response.read(jsonRpcResponseMessage.value, method)
    response.asOpt.value.right.value
  }

  private[this] def expectJsValue(sub: TestSubscriber.Probe[Message]): JsValue = {
    sub.request(1)
    val message = sub.expectNext()
    val jsonString = message.asTextMessage.getStrictText
    Json.parse(jsonString)
  }
}
