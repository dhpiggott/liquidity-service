package com.dhpcs.liquidity.server

import java.security.cert.{CertificateException, X509Certificate}
import javax.net.ssl.{SSLContext, X509TrustManager}

import akka.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.stream.scaladsl.{Flow, Keep}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import akka.stream.testkit.{TestPublisher, TestSubscriber}
import com.dhpcs.jsonrpc.{JsonRpcNotificationMessage, JsonRpcResponseMessage}
import com.dhpcs.liquidity.certgen.CertGen
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.protocol._
import okio.ByteString
import org.scalatest.EitherValues._
import org.scalatest.OptionValues._
import org.scalatest.{Inside, Matchers, fixture}
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.Await
import scala.concurrent.duration._

class LiquidityServerSpec
    extends fixture.WordSpec
    with CassandraPersistenceIntegrationTestFixtures
    with Matchers
    with Inside {

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

  override protected type FixtureParam = (TestSubscriber.Probe[Message], TestPublisher.Probe[Message])

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
    "reply with a CreateZoneResponse when sending a CreateZoneCommand" in { fixture =>
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
    "reply with a JoinZoneResponse when sending a JoinZoneCommand" in { fixture =>
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
      val zone = inside(expectResultResponse(sub, "createZone")) {
        case createZoneResponse:CreateZoneResponse =>
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
      inside(expectResultResponse(sub, "joinZone")) {
        case joinZoneResponse: JoinZoneResponse =>
          joinZoneResponse.zone shouldBe zone
          joinZoneResponse.connectedClients shouldBe Set(clientPublicKey)
      }
    }
  }

  private[this] def send(pub: TestPublisher.Probe[Message])(command: Command): Unit =
    pub.sendNext(
      TextMessage.Strict(
        Json.stringify(
          Json.toJson(
            Command.write(command, id = None)
          )))
    )

  private[this] def expectNotification(sub: TestSubscriber.Probe[Message]): Notification = {
    val jsValue                    = expectJsValue(sub)
    val jsonRpcNotificationMessage = jsValue.asOpt[JsonRpcNotificationMessage]
    val notification               = Notification.read(jsonRpcNotificationMessage.value)
    notification.value.asOpt.value
  }

  private[this] def expectResultResponse[A](sub: TestSubscriber.Probe[Message], method: String): ResultResponse = {
    val jsValue                = expectJsValue(sub)
    val jsonRpcResponseMessage = jsValue.asOpt[JsonRpcResponseMessage]
    val response               = Response.read(jsonRpcResponseMessage.value, method)
    response.asOpt.value.right.value
  }

  private[this] def expectJsValue(sub: TestSubscriber.Probe[Message]): JsValue = {
    val message    = sub.requestNext()
    val jsonString = message.asTextMessage.getStrictText
    Json.parse(jsonString)
  }
}
