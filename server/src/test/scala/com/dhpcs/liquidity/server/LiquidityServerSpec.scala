package com.dhpcs.liquidity.server

import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Keep}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import com.dhpcs.jsonrpc.{JsonRpcNotificationMessage, JsonRpcResponseMessage}
import com.dhpcs.liquidity.models._
import org.scalatest.WordSpec
import play.api.libs.json.Json

import scala.concurrent.duration._

class LiquidityServerSpec extends WordSpec {
  private[this] implicit val system = ActorSystem()
  private[this] implicit val materializer = ActorMaterializer()

  private[this] val port = {
    val serverSocket = ServerSocketChannel.open().socket()
    serverSocket.bind(new InetSocketAddress("127.0.0.1", 0))
    val port = serverSocket.getLocalPort
    serverSocket.close()
    port
  }

  private[this] def clientPublicKey: PublicKey = ???

  // To perform integration testing we'll need to launch Cassandra first, then generate a cert-key-pair for server
  // HTTPS, then generate cert-key-pair for client HTTPS, then use Akka HTTPS clients to test it (see
  // http://doc.akka.io/docs/akka/2.4.8/scala/http/client-side/client-https-support.html).
  "The WebSocket API" must {
    "send a SupportedVersionsNotification when connected" ignore {
      val flow = Flow.fromSinkAndSourceMat(
        TestSink.probe[Message],
        TestSource.probe[Message]
      )(Keep.both)
      val (_, (sub, pub)) = Http().singleWebSocketRequest(
        WebSocketRequest(s"ws://localhost:$port/ws"),
        flow
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
    }
    "send a KeepAliveNotification when left idle" ignore {
      val flow = Flow.fromSinkAndSourceMat(
        TestSink.probe[Message],
        TestSource.probe[Message]
      )(Keep.both)
      val (_, (sub, pub)) = Http().singleWebSocketRequest(
        WebSocketRequest(s"ws://localhost:$port/ws"),
        flow
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
    }
    "send a CreateZoneResponse after a CreateZoneCommand" ignore {
      val flow = Flow.fromSinkAndSourceMat(
        TestSink.probe[Message],
        TestSource.probe[Message]
      )(Keep.both)
      val (_, (sub, pub)) = Http().singleWebSocketRequest(
        WebSocketRequest(s"ws://localhost:$port/ws"),
        flow
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
    }
    "send a JoinZoneResponse after a JoinZoneCommand" ignore {
      val flow = Flow.fromSinkAndSourceMat(
        TestSink.probe[Message],
        TestSource.probe[Message]
      )(Keep.both)
      val (_, (sub, pub)) = Http().singleWebSocketRequest(
        WebSocketRequest(s"ws://localhost:$port/ws"),
        flow
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
    }
  }
}
