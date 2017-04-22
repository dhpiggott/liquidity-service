package com.dhpcs.liquidity.server

import java.net.InetAddress
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.{SSLSession, SSLSessionContext}
import javax.security.cert.X509Certificate

import akka.NotUsed
import akka.actor.ActorPath
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.headers.{`Remote-Address`, `Tls-Session-Info`}
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.model.{ContentType, RemoteAddress, StatusCodes}
import akka.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import akka.stream.scaladsl.Flow
import com.dhpcs.liquidity.certgen.CertGen
import com.dhpcs.liquidity.model.{AccountId, PublicKey, Zone, ZoneId}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.FreeSpec
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.Future

class HttpControllerSpec extends FreeSpec with HttpController with ScalatestRouteTest {

  private[this] val sslSession = {
    val (certificate, _) = CertGen.generateCertKey(subjectAlternativeName = None)
    new SSLSession {
      override def getPeerPort: Int                                = throw new NotImplementedError
      override def getCipherSuite: String                          = throw new NotImplementedError
      override def getPacketBufferSize: Int                        = throw new NotImplementedError
      override def getLocalPrincipal: Principal                    = throw new NotImplementedError
      override def getLocalCertificates: Array[Certificate]        = throw new NotImplementedError
      override def getId: Array[Byte]                              = throw new NotImplementedError
      override def getLastAccessedTime: Long                       = throw new NotImplementedError
      override def getPeerHost: String                             = throw new NotImplementedError
      override def getPeerCertificates: Array[Certificate]         = Array(certificate)
      override def getPeerPrincipal: Principal                     = throw new NotImplementedError
      override def getSessionContext: SSLSessionContext            = throw new NotImplementedError
      override def getValueNames: Array[String]                    = throw new NotImplementedError
      override def isValid: Boolean                                = throw new NotImplementedError
      override def getProtocol: String                             = throw new NotImplementedError
      override def invalidate(): Unit                              = throw new NotImplementedError
      override def getApplicationBufferSize: Int                   = throw new NotImplementedError
      override def getValue(s: String): AnyRef                     = throw new NotImplementedError
      override def removeValue(s: String): Unit                    = throw new NotImplementedError
      override def getPeerCertificateChain: Array[X509Certificate] = throw new NotImplementedError
      override def getCreationTime: Long                           = throw new NotImplementedError
      override def putValue(s: String, o: scala.Any): Unit         = throw new NotImplementedError
    }
  }

  override def testConfig: Config = ConfigFactory.defaultReference()

  "A LiquidityController" - {
    "will accept WebSocket connections" in {
      val wsProbe = WSProbe()
      WS("/ws", wsProbe.flow)
        .addHeader(
          `Remote-Address`(RemoteAddress(InetAddress.getLoopbackAddress))
        )
        .addHeader(
          `Tls-Session-Info`(sslSession)
        ) ~> route(enableClientRelay = true) ~> check {
        assert(isWebSocketUpgrade === true)
        val message = "Hello"
        wsProbe.sendMessage(message)
        wsProbe.expectMessage(message)
        wsProbe.sendCompletion()
        wsProbe.expectCompletion()
      }
    }
    "will provide status information" in {
      val getRequest = RequestBuilding.Get("/status")
      getRequest ~> route(enableClientRelay = true) ~> check {
        assert(status === StatusCodes.OK)
        assert(contentType === ContentType(`application/json`))
        assert(
          Json.parse(entityAs[String]) === Json.obj(
            "clients"         -> Json.obj(),
            "totalZonesCount" -> 0,
            "activeZones"     -> Json.obj(),
            "shardRegions"    -> Json.obj(),
            "clusterSharding" -> Json.obj()
          ))
      }
    }
  }

  override protected[this] def webSocketApi(ip: RemoteAddress, publicKey: PublicKey): Flow[Message, Message, NotUsed] =
    Flow[Message]

  override protected[this] def getStatus: Future[JsValue] =
    Future.successful(
      Json.obj(
        "clients"         -> Json.obj(),
        "totalZonesCount" -> 0,
        "activeZones"     -> Json.obj(),
        "shardRegions"    -> Json.obj(),
        "clusterSharding" -> Json.obj()
      ))
  override protected[this] def zoneOpt(zoneId: ZoneId): Future[Option[Zone]] = Future.successful(None)
  override protected[this] def balances(zoneId: ZoneId): Future[Map[AccountId, BigDecimal]] =
    Future.successful(Map.empty)
  override protected[this] def clients(zoneId: ZoneId): Future[Map[ActorPath, (Long, PublicKey)]] =
    Future.successful(Map.empty)

}
