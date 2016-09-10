package actors

import java.net.InetAddress
import java.security.KeyPairGenerator

import akka.actor.ActorRef
import akka.http.scaladsl.model.RemoteAddress
import akka.stream.ActorMaterializer
import akka.testkit.TestProbe
import com.dhpcs.jsonrpc.{JsonRpcNotificationMessage, JsonRpcResponseMessage}
import com.dhpcs.liquidity.models._
import org.scalatest.EitherValues._
import org.scalatest.OptionValues._
import org.scalatest.{Inside, MustMatchers, fixture}
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.duration._

class ClientConnectionSpec extends fixture.WordSpec
  with Inside with MustMatchers with ZoneValidatorShardRegionProvider {
  private[this] implicit val mat = ActorMaterializer()

  private[this] val ip = RemoteAddress(InetAddress.getLoopbackAddress)
  private[this] val publicKey = {
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    PublicKey(publicKeyBytes)
  }

  override protected type FixtureParam = (TestProbe, ActorRef)

  override protected def withFixture(test: OneArgTest) = {
    val upstreamTestProbe = TestProbe()
    val clientConnection = system.actorOf(
      ClientConnection.props(
        ip, publicKey, zoneValidatorShardRegion, keepAliveInterval = 3.seconds)(upstreamTestProbe.ref)
    )
    try withFixture(test.toNoArgTest((upstreamTestProbe, clientConnection)))
    finally system.stop(clientConnection)
  }

  "A ClientConnection" must {
    "send a SupportedVersionsNotification when connected" in { fixture =>
      val (upstreamTestProbe, _) = fixture
      expectNotification(upstreamTestProbe)(notification =>
        notification mustBe SupportedVersionsNotification(CompatibleVersionNumbers)
      )
    }
    "send a KeepAliveNotification when left idle" in { fixture =>
      val (upstreamTestProbe, _) = fixture
      expectNotification(upstreamTestProbe)(notification =>
        notification mustBe SupportedVersionsNotification(CompatibleVersionNumbers)
      )
      upstreamTestProbe.within(3.5.seconds)(
        expectNotification(upstreamTestProbe)(notification =>
          notification mustBe KeepAliveNotification
        )
      )
    }
    "send a CreateZoneResponse after a CreateZoneCommand" in { fixture =>
      val (upstreamTestProbe, clientConnection) = fixture
      expectNotification(upstreamTestProbe)(notification =>
        notification mustBe SupportedVersionsNotification(CompatibleVersionNumbers)
      )
      send(upstreamTestProbe, clientConnection)(
        CreateZoneCommand(
          equityOwnerPublicKey = publicKey,
          equityOwnerName = Some("Dave"),
          equityOwnerMetadata = None,
          equityAccountName = None,
          equityAccountMetadata = None,
          name = Some("Dave's Game")
        )
      )
      expectResponse(upstreamTestProbe, "createZone")(response =>
        response mustBe a[CreateZoneResponse]
      )
    }
    "send a JoinZoneResponse after a JoinZoneCommand" in { fixture =>
      val (upstreamTestProbe, clientConnection) = fixture
      expectNotification(upstreamTestProbe)(notification =>
        notification mustBe SupportedVersionsNotification(CompatibleVersionNumbers)
      )
      send(upstreamTestProbe, clientConnection)(
        CreateZoneCommand(
          equityOwnerPublicKey = publicKey,
          equityOwnerName = Some("Dave"),
          equityOwnerMetadata = None,
          equityAccountName = None,
          equityAccountMetadata = None,
          name = Some("Dave's Game")
        )
      )
      val zoneId = expectResponse(upstreamTestProbe, "createZone") { response =>
        response mustBe a[CreateZoneResponse]
        response.asInstanceOf[CreateZoneResponse].zone.id
      }
      send(upstreamTestProbe, clientConnection)(
        JoinZoneCommand(zoneId)
      )
      expectResponse(upstreamTestProbe, "joinZone")(response => inside(response) {
        case JoinZoneResponse(_, connectedClients) => connectedClients mustBe Set(publicKey)
      })
    }
  }

  private[this] def send(upstreamTestProbe: TestProbe, clientConnection: ActorRef)
                        (command: Command): Unit =
    upstreamTestProbe.send(
      clientConnection,
      Json.stringify(Json.toJson(
        Command.write(command, id = None)
      ))
    )

  private[this] def expectNotification(upstreamTestProbe: TestProbe)
                                      (f: Notification => Unit): Unit =
    expect(upstreamTestProbe) { json =>
      val jsonRpcNotificationMessage = json.asOpt[JsonRpcNotificationMessage]
      val notification = Notification.read(jsonRpcNotificationMessage.value)
      f(notification.value.asOpt.value)
    }

  private[this] def expectResponse[A](upstreamTestProbe: TestProbe, method: String)
                                     (f: Response => A): A =
    expect(upstreamTestProbe) { json =>
      val jsonRpcResponseMessage = json.asOpt[JsonRpcResponseMessage]
      val response = Response.read(jsonRpcResponseMessage.value, method)
      f(response.asOpt.value.right.value)
    }

  private[this] def expect[A](upstreamTestProbe: TestProbe)
                             (f: JsValue => A): A = {
    val jsonString = upstreamTestProbe.expectMsgType[String]
    val json = Json.parse(jsonString)
    f(json)
  }
}
