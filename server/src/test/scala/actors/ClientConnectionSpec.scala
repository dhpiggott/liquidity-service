package actors

import java.net.InetAddress
import java.security.KeyPairGenerator

import akka.http.scaladsl.model.RemoteAddress
import akka.http.scaladsl.model.ws.TextMessage
import akka.testkit.TestProbe
import com.dhpcs.jsonrpc.{JsonRpcNotificationMessage, JsonRpcResponseMessage}
import com.dhpcs.liquidity.models._
import org.scalatest.WordSpec
import play.api.libs.json.Json

import scala.concurrent.duration._

class ClientConnectionSpec extends WordSpec with ZoneValidatorShardRegionProvider {
  private[this] val ip = RemoteAddress(InetAddress.getLoopbackAddress)
  private[this] val publicKey = {
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    PublicKey(publicKeyBytes)
  }

  "A ClientConnection" must {
    "send a SupportedVersionsNotification when connected" in {
      val upstreamTestProbe = TestProbe()
      val clientConnection = system.actorOf(
        ClientConnection.props(ip, publicKey, zoneValidatorShardRegion)(upstreamTestProbe.ref)
      )
      upstreamTestProbe.expectMsgPF() {
        case TextMessage.Strict(jsonString)
          if Json.parse(jsonString)
            .asOpt[JsonRpcNotificationMessage]
            .flatMap(Notification.read)
            .exists(_.asOpt.exists(_.isInstanceOf[SupportedVersionsNotification])) =>
      }
      system.stop(clientConnection)
    }
    "send a KeepAliveNotification when left idle" in {
      val upstreamTestProbe = TestProbe()
      val clientConnection = system.actorOf(
        ClientConnection.props(ip, publicKey, zoneValidatorShardRegion)(upstreamTestProbe.ref)
      )
      upstreamTestProbe.expectMsgPF() {
        case TextMessage.Strict(jsonString)
          if Json.parse(jsonString)
            .asOpt[JsonRpcNotificationMessage]
            .flatMap(Notification.read)
            .exists(_.asOpt.exists(_.isInstanceOf[SupportedVersionsNotification])) =>
      }
      upstreamTestProbe.expectMsgPF(35.seconds) {
        case TextMessage.Strict(jsonString)
          if Json.parse(jsonString)
            .asOpt[JsonRpcNotificationMessage]
            .flatMap(Notification.read)
            .exists(_.asOpt.exists(_ == KeepAliveNotification)) =>
      }
      system.stop(clientConnection)
    }
    "send a CreateZoneResponse after a CreateZoneCommand" in {
      val upstreamTestProbe = TestProbe()
      val clientConnection = system.actorOf(
        ClientConnection.props(ip, publicKey, zoneValidatorShardRegion)(upstreamTestProbe.ref)
      )
      upstreamTestProbe.expectMsgPF() {
        case TextMessage.Strict(jsonString)
          if Json.parse(jsonString)
            .asOpt[JsonRpcNotificationMessage]
            .flatMap(Notification.read)
            .exists(_.asOpt.exists(_.isInstanceOf[SupportedVersionsNotification])) =>
      }
      upstreamTestProbe.send(
        clientConnection,
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
      upstreamTestProbe.expectMsgPF() {
        case TextMessage.Strict(jsonString)
          if Json.parse(jsonString).asOpt[JsonRpcResponseMessage]
            .map(Response.read(_, "createZone"))
            .flatMap(_.asOpt)
            .flatMap(_.right.toOption)
            .exists(_.isInstanceOf[CreateZoneResponse]) =>
      }
      system.stop(clientConnection)
    }
    "send a JoinZoneResponse after a JoinZoneCommand" in {
      val upstreamTestProbe = TestProbe()
      val clientConnection = system.actorOf(
        ClientConnection.props(ip, publicKey, zoneValidatorShardRegion)(upstreamTestProbe.ref)
      )
      upstreamTestProbe.expectMsgPF() {
        case TextMessage.Strict(jsonString)
          if Json.parse(jsonString)
            .asOpt[JsonRpcNotificationMessage]
            .flatMap(Notification.read)
            .exists(_.asOpt.exists(_.isInstanceOf[SupportedVersionsNotification])) =>
      }
      upstreamTestProbe.send(
        clientConnection,
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
      val zoneId = upstreamTestProbe.expectMsgPF() {
        case TextMessage.Strict(jsonString)
          if Json.parse(jsonString)
            .asOpt[JsonRpcResponseMessage]
            .map(Response.read(_, "createZone"))
            .flatMap(_.asOpt)
            .flatMap(_.right.toOption)
            .exists(_.isInstanceOf[CreateZoneResponse]) =>
          Json.parse(jsonString)
            .asOpt[JsonRpcResponseMessage]
            .map(Response.read(_, "createZone"))
            .flatMap(_.asOpt)
            .flatMap(_.right.toOption)
            .get.asInstanceOf[CreateZoneResponse].zone.id
      }
      upstreamTestProbe.send(
        clientConnection,
        TextMessage.Strict(Json.stringify(Json.toJson(
          Command.write(
            JoinZoneCommand(
              zoneId
            ),
            None
          )
        )))
      )
      upstreamTestProbe.expectMsgPF() {
        case TextMessage.Strict(jsonString)
          if Json.parse(jsonString)
            .asOpt[JsonRpcResponseMessage]
            .map(Response.read(_, "joinZone"))
            .flatMap(_.asOpt)
            .flatMap(_.right.toOption)
            .exists(_.isInstanceOf[JoinZoneResponse]) =>
      }
    }
  }
}
