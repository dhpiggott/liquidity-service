package com.dhpcs.liquidity.server

import java.net.InetAddress
import java.security.KeyPairGenerator

import akka.NotUsed
import akka.actor.ActorPath
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.headers.`Remote-Address`
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.model.{ContentType, RemoteAddress, StatusCodes}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import akka.stream.scaladsl.Flow
import com.dhpcs.liquidity.model.{AccountId, PublicKey, Zone, ZoneId}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.FreeSpec
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.Future

class HttpControllerSpec extends FreeSpec with ScalatestRouteTest with HttpController {

  override def testConfig: Config = ConfigFactory.defaultReference()

  "A LiquidityController" - {
    "will provide status information" in {
      val getRequest = RequestBuilding.Get("/status")
      getRequest ~> route(enableClientRelay = true) ~> check {
        status === StatusCodes.OK
        contentType === ContentType(`application/json`)
        Json.parse(entityAs[String]) === Json.obj()
      }
    }
    "will accept WebSocket connections" in {
      val wsProbe = WSProbe()
      WS("/ws", wsProbe.flow).addHeader(
        `Remote-Address`(RemoteAddress(InetAddress.getLoopbackAddress))
      ) ~> route(enableClientRelay = true) ~> check {
        isWebSocketUpgrade === true
        val message = "Hello"
        wsProbe.sendMessage(message)
        wsProbe.expectMessage(message)
        wsProbe.sendCompletion()
        wsProbe.expectCompletion()
      }
    }
  }

  override protected[this] def getStatus: Future[JsValue] = Future.successful(Json.obj())

  override protected[this] def webSocketApi(ip: RemoteAddress, publicKey: PublicKey): Flow[Message, Message, NotUsed] =
    Flow[Message]

  override protected[this] def extractClientPublicKey(ip: RemoteAddress)(route: (PublicKey) => Route): Route =
    route(
      PublicKey(
        KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
      )
    )

  override protected[this] def zoneOpt(zoneId: ZoneId): Future[Option[Zone]] = Future.successful(None)

  override protected[this] def balances(zoneId: ZoneId): Future[Map[AccountId, BigDecimal]] =
    Future.successful(Map.empty)

  override protected[this] def clients(zoneId: ZoneId): Future[Map[ActorPath, (Long, PublicKey)]] =
    Future.successful(Map.empty)

}
