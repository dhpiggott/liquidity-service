package com.dhpcs.liquidity.server

import java.net.InetAddress
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.{SSLSession, SSLSessionContext}
import javax.security.cert.X509Certificate

import akka.NotUsed
import akka.http.scaladsl.model.RemoteAddress
import akka.http.scaladsl.model.headers.{`Remote-Address`, `Tls-Session-Info`}
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import akka.stream.scaladsl.Flow
import com.dhpcs.liquidity.model.PublicKey
import com.dhpcs.liquidity.testkit.TestKit
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.FreeSpec

class LegacyHttpsControllerSpec extends FreeSpec with LegacyHttpsController with ScalatestRouteTest {

  private[this] val sslSession = {
    val (certificate, _) = TestKit.generateCertKey(subjectAlternativeName = None)
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

  "The LegacyHttpsController" - (
    "will accept legacy WebSocket connections" in {
      val wsProbe = WSProbe()
      WS("/ws", wsProbe.flow)
        .addHeader(
          `Remote-Address`(RemoteAddress(InetAddress.getLoopbackAddress))
        )
        .addHeader(
          `Tls-Session-Info`(sslSession)
        ) ~> httpsRoutes(enableClientRelay = true) ~> check {
        assert(isWebSocketUpgrade === true)
        val message = "Hello"
        wsProbe.sendMessage(message)
        wsProbe.expectMessage(message)
        wsProbe.sendCompletion()
        wsProbe.expectCompletion()
      }
    }
  )

  override protected[this] def legacyWebSocketApi(ip: RemoteAddress,
                                                  publicKey: PublicKey): Flow[Message, Message, NotUsed] =
    Flow[Message]

}
