package com.dhpcs.liquidity.server.actor

import java.net.InetAddress
import java.security.KeyPairGenerator

import akka.actor.ActorRef
import akka.http.scaladsl.model.RemoteAddress
import akka.testkit.TestProbe
import com.dhpcs.jsonrpc.{JsonRpcNotificationMessage, JsonRpcResponseMessage}
import com.dhpcs.liquidity.actor.protocol.{
  AuthenticatedCommandWithIds,
  EnvelopedAuthenticatedCommandWithIds,
  ResponseWithIds
}
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.server.InMemPersistenceTestFixtures
import com.dhpcs.liquidity.ws.protocol._
import org.scalatest.EitherValues._
import org.scalatest.OptionValues._
import org.scalatest.{Inside, Matchers, Outcome, fixture}
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.duration._

class ClientConnectionActorSpec extends fixture.WordSpec with InMemPersistenceTestFixtures with Inside with Matchers {

  private[this] val ip = RemoteAddress(InetAddress.getLoopbackAddress)
  private[this] val publicKey = {
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    PublicKey(publicKeyBytes)
  }

  override protected type FixtureParam = (TestProbe, TestProbe, TestProbe, ActorRef)

  override protected def withFixture(test: OneArgTest): Outcome = {
    val sinkTestProbe                     = TestProbe()
    val zoneValidatorShardRegionTestProbe = TestProbe()
    val upstreamTestProbe                 = TestProbe()
    val clientConnection = system.actorOf(
      ClientConnectionActor.props(ip, publicKey, zoneValidatorShardRegionTestProbe.ref, keepAliveInterval = 3.seconds)(
        upstreamTestProbe.ref)
    )
    sinkTestProbe.send(clientConnection, ClientConnectionActor.ActorSinkInit)
    sinkTestProbe.expectMsg(ClientConnectionActor.ActorSinkAck)
    try withFixture(
      test.toNoArgTest((sinkTestProbe, zoneValidatorShardRegionTestProbe, upstreamTestProbe, clientConnection))
    )
    finally system.stop(clientConnection)
  }

  "A ClientConnectionActor" should {
    "send a SupportedVersionsNotification when connected" in { fixture =>
      val (_, _, upstreamTestProbe, _) = fixture
      expectNotification(upstreamTestProbe) shouldBe SupportedVersionsNotification(CompatibleVersionNumbers)
    }
    "send a KeepAliveNotification when left idle" in { fixture =>
      val (_, _, upstreamTestProbe, _) = fixture
      expectNotification(upstreamTestProbe) shouldBe SupportedVersionsNotification(CompatibleVersionNumbers)
      upstreamTestProbe.within(3.5.seconds)(
        expectNotification(upstreamTestProbe) shouldBe KeepAliveNotification
      )
    }
    "reply with a CreateZoneResponse when forwarding a CreateZoneCommand" in { fixture =>
      val (sinkTestProbe, zoneValidatorShardRegionTestProbe, upstreamTestProbe, clientConnection) = fixture
      expectNotification(upstreamTestProbe) shouldBe SupportedVersionsNotification(CompatibleVersionNumbers)
      val command = CreateZoneCommand(
        equityOwnerPublicKey = publicKey,
        equityOwnerName = Some("Dave"),
        equityOwnerMetadata = None,
        equityAccountName = None,
        equityAccountMetadata = None,
        name = Some("Dave's Game")
      )
      val correlationId = Some(Right(BigDecimal(0)))
      send(sinkTestProbe, clientConnection)(command, correlationId)
      val zoneId = inside(zoneValidatorShardRegionTestProbe.expectMsgType[EnvelopedAuthenticatedCommandWithIds]) {
        case envelopedMessage @ EnvelopedAuthenticatedCommandWithIds(_, authenticatedCommandWithIds) =>
          authenticatedCommandWithIds shouldBe
            AuthenticatedCommandWithIds(publicKey, command, correlationId, sequenceNumber = 1L, deliveryId = 1L)
          envelopedMessage.zoneId
      }
      val resultResponse = CreateZoneResponse({
        val created = System.currentTimeMillis
        Zone(
          id = zoneId,
          equityAccountId = AccountId(0),
          members = Map(MemberId(0)   -> Member(MemberId(0), publicKey, name = Some("Dave"))),
          accounts = Map(AccountId(0) -> Account(AccountId(0), ownerMemberIds = Set(MemberId(0)))),
          transactions = Map.empty,
          created = created,
          expires = created + 2.days.toMillis,
          name = Some("Dave's Game"),
          metadata = None
        )
      })
      zoneValidatorShardRegionTestProbe.send(
        clientConnection,
        ResponseWithIds(Right(resultResponse), correlationId, sequenceNumber = 1L, deliveryId = 1L)
      )
      expectResponse(upstreamTestProbe, "createZone") shouldBe resultResponse
    }
  }

  private[this] def send(sinkTestProbe: TestProbe, clientConnection: ActorRef)(
      command: Command,
      correlationId: Option[Either[String, BigDecimal]]): Unit = {
    sinkTestProbe.send(
      clientConnection,
      Json.stringify(
        Json.toJson(
          Command.write(command, id = correlationId)
        ))
    )
    sinkTestProbe.expectMsg(ClientConnectionActor.ActorSinkAck)
  }

  private[this] def expectNotification(upstreamTestProbe: TestProbe): Notification = {
    val jsValue                    = expectJsValue(upstreamTestProbe)
    val jsonRpcNotificationMessage = jsValue.asOpt[JsonRpcNotificationMessage]
    val notification               = Notification.read(jsonRpcNotificationMessage.value)
    notification.value.asOpt.value
  }

  private[this] def expectResponse(upstreamTestProbe: TestProbe, method: String): ResultResponse = {
    val jsValue                = expectJsValue(upstreamTestProbe)
    val jsonRpcResponseMessage = jsValue.asOpt[JsonRpcResponseMessage]
    val response               = Response.read(jsonRpcResponseMessage.value, method)
    response.asOpt.value.right.value
  }

  private[this] def expectJsValue(upstreamTestProbe: TestProbe): JsValue = {
    val jsonString = upstreamTestProbe.expectMsgType[String]
    Json.parse(jsonString)
  }
}
