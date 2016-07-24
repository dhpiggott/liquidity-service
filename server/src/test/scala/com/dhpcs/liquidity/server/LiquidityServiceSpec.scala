package com.dhpcs.liquidity.server

import java.net.InetAddress
import java.security.KeyPairGenerator

import akka.NotUsed
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.headers.`Remote-Address`
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.model.{ContentType, HttpEntity, RemoteAddress, StatusCodes}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import akka.stream.scaladsl.Flow
import com.dhpcs.liquidity.models.PublicKey
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{Matchers, WordSpec}

import scala.concurrent.Future

class LiquidityServiceSpec extends WordSpec
  with Matchers
  with ScalatestRouteTest
  with LiquidityService {

  "The Liquidity API" must {
    "provide status information" in {
      val getRequest = RequestBuilding.Get("/status")
      getRequest ~> route ~> check {
        status shouldBe StatusCodes.OK
        contentType shouldBe ContentType(`application/json`)
        entityAs[String] shouldBe ""
      }
    }
    "accept WebSocket connections" in {
      val wsProbe = WSProbe()
      WS("/ws", wsProbe.flow).addHeader(
        `Remote-Address`(RemoteAddress(InetAddress.getLoopbackAddress))
      ) ~> route ~> check {
        isWebSocketUpgrade shouldBe true
        val message = "Hello"
        wsProbe.sendMessage(message)
        wsProbe.expectMessage(message)
        wsProbe.sendCompletion()
        wsProbe.expectCompletion()
      }
    }
  }

  override def testConfig: Config = ConfigFactory.defaultReference()

  override protected[this] def getStatus: ToResponseMarshallable = Future.successful(
    HttpEntity(
      ContentType(`application/json`),
      ""
    )
  )

  override protected[this] def webSocketApi(ip: RemoteAddress, publicKey: PublicKey): Flow[Message, Message, NotUsed] =
    Flow[Message]

  override protected[this] def extractClientPublicKey(ip: RemoteAddress)(route: (PublicKey) => Route): Route =
    route(
      PublicKey(
        KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
      )
    )
}
