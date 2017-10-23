package com.dhpcs.liquidity.server

import java.net.InetAddress

import akka.NotUsed
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.headers.`Remote-Address`
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.model.{ContentType, RemoteAddress, StatusCodes}
import akka.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import akka.stream.scaladsl.{Flow, Source}
import com.dhpcs.liquidity.actor.protocol.clientmonitor._
import com.dhpcs.liquidity.actor.protocol.zonemonitor._
import com.dhpcs.liquidity.model.{AccountId, Zone, ZoneId}
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.server.HttpController.EventEnvelope
import com.typesafe.config.{Config, ConfigFactory}
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport._
import org.scalatest.{FreeSpec, Inside}
import play.api.libs.json.{JsObject, Json}

import scala.collection.immutable.Seq
import scala.concurrent.Future

class HttpControllerSpec extends FreeSpec with HttpController with ScalatestRouteTest with Inside {

  override def testConfig: Config = ConfigFactory.defaultReference()

  "The HttpController" - {
    "will provide version information" in {
      val getRequest = RequestBuilding.Get("/version")
      getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
        assert(status === StatusCodes.OK)
        assert(contentType === ContentType(`application/json`))
        val keys = entityAs[JsObject].value.keySet
        assert(keys.contains("version"))
        assert(keys.contains("builtAtString"))
        assert(keys.contains("builtAtMillis"))
      }
    }
    "will accept WebSocket connections" in {
      val wsProbe = WSProbe()
      WS("/ws", wsProbe.flow)
        .addHeader(
          `Remote-Address`(RemoteAddress(InetAddress.getLoopbackAddress))
        ) ~> httpRoutes(enableClientRelay = true) ~> check {
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
      getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
        assert(status === StatusCodes.OK)
        assert(contentType === ContentType(`application/json`))
        assert(
          entityAs[JsObject] === Json.obj(
            "clients" -> Json.obj(
              "count"                 -> 0,
              "publicKeyFingerprints" -> Seq.empty[String]
            ),
            "zones" -> Json.obj(
              "count" -> 0,
              "zones" -> Seq.empty[JsObject]
            )
          ))
      }
    }
  }

  override protected[this] def events(persistenceId: String,
                                      fromSequenceNr: Long,
                                      toSequenceNr: Long): Source[EventEnvelope, NotUsed] = Source.empty[EventEnvelope]

  override protected[this] def zoneState(zoneId: ZoneId): Future[proto.persistence.zone.ZoneState] =
    Future.successful(proto.persistence.zone.ZoneState(zone = None, balances = Map.empty, connectedClients = Map.empty))

  override protected[this] def webSocketApi(remoteAddress: InetAddress): Flow[Message, Message, NotUsed] = Flow[Message]

  override protected[this] def getActiveClientSummaries: Future[Set[ActiveClientSummary]] = Future.successful(Set.empty)

  override protected[this] def getActiveZoneSummaries: Future[Set[ActiveZoneSummary]] = Future.successful(Set.empty)

  override protected[this] def getZone(zoneId: ZoneId): Option[Zone] = None

  override protected[this] def getBalances(zoneId: ZoneId): Map[AccountId, BigDecimal] = Map.empty

}
