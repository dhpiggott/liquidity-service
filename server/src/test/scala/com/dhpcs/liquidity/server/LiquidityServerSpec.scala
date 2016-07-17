package com.dhpcs.liquidity.server

import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.security.cert.{Certificate, CertificateException, X509Certificate}
import java.security.{KeyStore, PrivateKey}
import javax.net.ssl.{KeyManager, KeyManagerFactory, SSLContext, X509TrustManager}

import actors.ZoneValidatorShardRegionProvider
import akka.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Keep}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import com.dhpcs.jsonrpc.{JsonRpcNotificationMessage, JsonRpcResponseMessage}
import com.dhpcs.liquidity.CertGen
import com.dhpcs.liquidity.models._
import com.dhpcs.liquidity.server.LiquidityServerSpec._
import com.typesafe.config.ConfigFactory
import okio.ByteString
import org.scalatest.WordSpec
import play.api.libs.json.Json

import scala.concurrent.Await
import scala.concurrent.duration._

object LiquidityServerSpec {
  private final val KeyStoreEntryAlias = "identity"
  private final val KeyStoreEntryPassword = Array.emptyCharArray

  private def freePort(): Int = {
    val serverSocket = ServerSocketChannel.open().socket()
    serverSocket.bind(new InetSocketAddress("127.0.0.1", 0))
    val port = serverSocket.getLocalPort
    serverSocket.close()
    port
  }

  private def createKeyManagers(certificate: Certificate,
                                privateKey: PrivateKey): Array[KeyManager] = {
    val keyStore = KeyStore.getInstance("JKS")
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

class LiquidityServerSpec extends WordSpec with ZoneValidatorShardRegionProvider {
  override protected[this] def config =
    ConfigFactory.parseString(
      s"""
         |liquidity.http {
         |  interface = "0.0.0.0"
         |  port = "$akkaHttpPort"
         |}
    """.stripMargin
    ).withFallback(super.config)

  private[this] lazy val akkaHttpPort = freePort()

  private[this] val (serverPublicKey, serverKeyManagers) = {
    val (certificate, privateKey) = CertGen.generateCertKeyPair("localhost")
    (certificate.getPublicKey, createKeyManagers(certificate, privateKey))
  }

  private[this] val (clientHttpsConnectionContext, clientPublicKey) = {
    val (certificate, privateKey) = CertGen.generateCertKeyPair("LiquidityServerSpec")
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

  private[this] val baseUrl = s"wss://localhost:$akkaHttpPort"

  private[this] implicit val materializer = ActorMaterializer()

  "The WebSocket API" must {
    "send a SupportedVersionsNotification when connected" in {
      val server = new LiquidityServer(
        config,
        zoneValidatorShardRegion,
        serverKeyManagers
      )
      val flow = Flow.fromSinkAndSourceMat(
        TestSink.probe[Message],
        TestSource.probe[Message]
      )(Keep.both)
      val (_, (sub, pub)) = Http().singleWebSocketRequest(
        WebSocketRequest(s"$baseUrl/ws"),
        flow,
        clientHttpsConnectionContext
      )
      sub.request(1)
      sub.expectNextPF {
        case TextMessage.Strict(text)
          if Json.parse(text)
            .asOpt[JsonRpcNotificationMessage]
            .flatMap(Notification.read)
            .exists(_.asOpt.exists(_.isInstanceOf[SupportedVersionsNotification])) =>
      }
      pub.sendComplete()
      Await.result(server.shutdown(), Duration.Inf)
    }
    "send a KeepAliveNotification when left idle" in {
      val server = new LiquidityServer(
        config,
        zoneValidatorShardRegion,
        serverKeyManagers
      )
      val flow = Flow.fromSinkAndSourceMat(
        TestSink.probe[Message],
        TestSource.probe[Message]
      )(Keep.both)
      val (_, (sub, pub)) = Http().singleWebSocketRequest(
        WebSocketRequest(s"$baseUrl/ws"),
        flow,
        clientHttpsConnectionContext
      )
      sub.request(1)
      sub.expectNextPF {
        case TextMessage.Strict(text)
          if Json.parse(text)
            .asOpt[JsonRpcNotificationMessage]
            .flatMap(Notification.read)
            .exists(_.asOpt.exists(_.isInstanceOf[SupportedVersionsNotification])) =>
      }
      sub.within(35.seconds) {
        sub.request(1)
        sub.expectNextPF {
          case TextMessage.Strict(text)
            if Json.parse(text)
              .asOpt[JsonRpcNotificationMessage]
              .flatMap(Notification.read)
              .exists(_.asOpt.exists(_ == KeepAliveNotification)) =>
        }
      }
      pub.sendComplete()
      Await.result(server.shutdown(), Duration.Inf)
    }
    "send a CreateZoneResponse after a CreateZoneCommand" in {
      val server = new LiquidityServer(
        config,
        zoneValidatorShardRegion,
        serverKeyManagers
      )
      val flow = Flow.fromSinkAndSourceMat(
        TestSink.probe[Message],
        TestSource.probe[Message]
      )(Keep.both)
      val (_, (sub, pub)) = Http().singleWebSocketRequest(
        WebSocketRequest(s"$baseUrl/ws"),
        flow,
        clientHttpsConnectionContext
      )
      sub.request(1)
      sub.expectNextPF {
        case TextMessage.Strict(text)
          if Json.parse(text).asOpt[JsonRpcNotificationMessage]
            .flatMap(Notification.read)
            .flatMap(_.asOpt)
            .exists(_.isInstanceOf[SupportedVersionsNotification]) =>
      }
      pub.sendNext(
        TextMessage.Strict(Json.stringify(Json.toJson(
          Command.write(
            CreateZoneCommand(
              clientPublicKey,
              Some("Dave"),
              None,
              None,
              None,
              Some("Dave's Game")
            ),
            None
          )
        )))
      )
      sub.request(1)
      sub.expectNextPF {
        case TextMessage.Strict(text)
          if Json.parse(text).asOpt[JsonRpcResponseMessage]
            .map(Response.read(_, "createZone"))
            .flatMap(_.asOpt)
            .flatMap(_.right.toOption)
            .exists(_.isInstanceOf[CreateZoneResponse]) =>
      }
      pub.sendComplete()
      Await.result(server.shutdown(), Duration.Inf)
    }
    "send a JoinZoneResponse after a JoinZoneCommand" in {
      val server = new LiquidityServer(
        config,
        zoneValidatorShardRegion,
        serverKeyManagers
      )
      val flow = Flow.fromSinkAndSourceMat(
        TestSink.probe[Message],
        TestSource.probe[Message]
      )(Keep.both)
      val (_, (sub, pub)) = Http().singleWebSocketRequest(
        WebSocketRequest(s"$baseUrl/ws"),
        flow,
        clientHttpsConnectionContext
      )
      sub.request(1)
      sub.expectNextPF {
        case TextMessage.Strict(text)
          if Json.parse(text).asOpt[JsonRpcNotificationMessage]
            .flatMap(Notification.read)
            .flatMap(_.asOpt)
            .exists(_.isInstanceOf[SupportedVersionsNotification]) =>
      }
      pub.sendNext(
        TextMessage.Strict(Json.stringify(Json.toJson(
          Command.write(
            CreateZoneCommand(
              clientPublicKey,
              Some("Dave"),
              None,
              None,
              None,
              Some("Dave's Game")
            ),
            None
          )
        )))
      )
      sub.request(1)
      val zoneId = sub.expectNextPF {
        case TextMessage.Strict(text)
          if Json.parse(text).asOpt[JsonRpcResponseMessage]
            .map(Response.read(_, "createZone"))
            .flatMap(_.asOpt)
            .flatMap(_.right.toOption)
            .exists(_.isInstanceOf[CreateZoneResponse]) =>
          Json.parse(text).asOpt[JsonRpcResponseMessage]
            .map(Response.read(_, "createZone"))
            .flatMap(_.asOpt)
            .flatMap(_.right.toOption)
            .get.asInstanceOf[CreateZoneResponse].zone.id
      }
      pub.sendNext(
        TextMessage.Strict(Json.stringify(Json.toJson(
          Command.write(
            JoinZoneCommand(
              zoneId
            ),
            None
          )
        )))
      )
      sub.request(1)
      sub.expectNextPF {
        case TextMessage.Strict(text)
          if Json.parse(text).asOpt[JsonRpcResponseMessage]
            .map(Response.read(_, "joinZone"))
            .flatMap(_.asOpt)
            .flatMap(_.right.toOption)
            .exists(_.isInstanceOf[JoinZoneResponse]) =>
      }
      pub.sendComplete()
      Await.result(server.shutdown(), Duration.Inf)
    }
  }
}
