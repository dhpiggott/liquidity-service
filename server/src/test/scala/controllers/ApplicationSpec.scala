package controllers

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import akka.stream.scaladsl.{Flow, Keep}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import com.dhpcs.jsonrpc.{JsonRpcNotificationMessage, JsonRpcResponseMessage}
import com.dhpcs.liquidity.models._
import controllers.ApplicationSpec.{ClientNginxPemCertificateHeaderString, ClientPublicKey}
import org.apache.commons.codec.binary.Base64
import org.scalatestplus.play.{OneServerPerSuite, PlaySpec}
import play.api.libs.json.Json

import scala.concurrent.duration._
import scala.io.Source

object ApplicationSpec {
  private final val ClientNginxPemCertificateHeaderString = {
    val lines = Source.fromFile("nginx/liquidity.dhpcs.com.crt").getLines.toList
    // Nginx prepends all but the first line with tabs when encoding certificates as PEM strings in its
    // X-SSL-Client-Cert header.
    (lines.head :: lines.tail.map("\t" + _)).mkString
  }

  private final val ClientPublicKey = PublicKey(
    CertificateFactory.getInstance("X.509").generateCertificate(
      new ByteArrayInputStream(
        Base64.decodeBase64(
          ClientNginxPemCertificateHeaderString
            .stripPrefix("-----BEGIN CERTIFICATE-----")
            .stripSuffix("-----End CERTIFICATE-----")
        )
      )
    ).getPublicKey.getEncoded
  )
}

class ApplicationSpec extends PlaySpec with OneServerPerSuite {
  private[this] implicit val system = app.actorSystem
  private[this] implicit val mat = app.materializer

  "The WebSocket API" must {
    "send a SupportedVersionsNotification when connected" in {
      val flow = Flow.fromSinkAndSourceMat(
        TestSink.probe[Message],
        TestSource.probe[Message]
      )(Keep.both)
      val (_, (sub, pub)) = Http().singleWebSocketRequest(
        WebSocketRequest(
          s"ws://localhost:$port/ws",
          List(
            RawHeader("X-SSL-Client-Cert", ClientNginxPemCertificateHeaderString)
          )
        ),
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
    "send a KeepAliveNotification when left idle" in {
      val flow = Flow.fromSinkAndSourceMat(
        TestSink.probe[Message],
        TestSource.probe[Message]
      )(Keep.both)
      val (_, (sub, pub)) = Http().singleWebSocketRequest(
        WebSocketRequest(
          s"ws://localhost:$port/ws",
          List(
            RawHeader("X-SSL-Client-Cert", ClientNginxPemCertificateHeaderString)
          )
        ),
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
    "send a CreateZoneResponse after a CreateZoneCommand" in {
      val flow = Flow.fromSinkAndSourceMat(
        TestSink.probe[Message],
        TestSource.probe[Message]
      )(Keep.both)
      val (_, (sub, pub)) = Http().singleWebSocketRequest(
        WebSocketRequest(
          s"ws://localhost:$port/ws",
          List(
            RawHeader("X-SSL-Client-Cert", ClientNginxPemCertificateHeaderString)
          )
        ),
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
              ClientPublicKey,
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
    "send a JoinZoneResponse after a JoinZoneCommand" in {
      val flow = Flow.fromSinkAndSourceMat(
        TestSink.probe[Message],
        TestSource.probe[Message]
      )(Keep.both)
      val (_, (sub, pub)) = Http().singleWebSocketRequest(
        WebSocketRequest(
          s"ws://localhost:$port/ws",
          List(
            RawHeader("X-SSL-Client-Cert", ClientNginxPemCertificateHeaderString)
          )
        ),
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
              ClientPublicKey,
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
