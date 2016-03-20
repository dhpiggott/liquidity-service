package controllers

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Keep
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import com.dhpcs.jsonrpc.{JsonRpcNotificationMessage, JsonRpcResponseMessage}
import com.dhpcs.liquidity.models._
import org.apache.commons.codec.binary.Base64
import org.scalatestplus.play.{OneServerPerSuite, PlaySpec}
import play.api.libs.concurrent.Akka
import play.api.libs.json.Json

import scala.io.Source

class ApplicationSpec extends PlaySpec with OneServerPerSuite {

  private val clientCertificateString = Source.fromFile("nginx/liquidity.dhpcs.com.crt").mkString.replaceAllLiterally("\n", "")
  private val publicKey = PublicKey(
    CertificateFactory.getInstance("X.509").generateCertificate(
      new ByteArrayInputStream(
        Base64.decodeBase64(
          clientCertificateString.stripPrefix("-----BEGIN CERTIFICATE-----").stripSuffix("-----End CERTIFICATE-----")
        )
      )
    ).getPublicKey.getEncoded
  )

  private implicit val system = Akka.system(app)
  private implicit val materializer = ActorMaterializer()

  "The WebSocket API" must {
    "send a SupportedVersionsNotification when connected" in {
      val webSocketFlow = Http().webSocketClientFlow(
        WebSocketRequest(
          s"ws://localhost:$port/ws",
          List(
            RawHeader("X-SSL-Client-Cert", clientCertificateString)
          )
        )
      )
      val sub = TestSource.probe[Message]
        .via(webSocketFlow)
        .toMat(TestSink.probe[Message])(Keep.right)
        .run()
      sub.request(1)
      sub.expectNextPF {
        case TextMessage.Strict(text)
          if Json.parse(text)
            .asOpt[JsonRpcNotificationMessage]
            .flatMap(Notification.read)
            .exists(_.asOpt.exists(_.isInstanceOf[SupportedVersionsNotification])) =>
      }
    }
    "send a CreateZoneReponse after a CreateZoneCommand" in {
      val webSocketFlow = Http().webSocketClientFlow(
        WebSocketRequest(
          s"ws://localhost:$port/ws",
          List(
            RawHeader("X-SSL-Client-Cert", clientCertificateString)
          )
        )
      )
      val (pub, sub) = TestSource.probe[Message]
        .via(webSocketFlow)
        .toMat(TestSink.probe[Message])(Keep.both)
        .run()
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
              publicKey,
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
    }
    "send a JoinZoneReponse after a JoinZoneCommand" in {
      val webSocketFlow = Http().webSocketClientFlow(
        WebSocketRequest(
          s"ws://localhost:$port/ws",
          List(
            RawHeader("X-SSL-Client-Cert", clientCertificateString)
          )
        )
      )
      val (pub, sub) = TestSource.probe[Message]
        .via(webSocketFlow)
        .toMat(TestSink.probe[Message])(Keep.both)
        .run()
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
              publicKey,
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
    }
  }

}
