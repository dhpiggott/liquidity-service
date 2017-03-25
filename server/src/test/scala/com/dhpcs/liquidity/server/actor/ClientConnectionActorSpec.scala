package com.dhpcs.liquidity.server.actor

import java.net.InetAddress
import java.security.KeyPairGenerator

import akka.actor.{ActorRef, Deploy}
import akka.http.scaladsl.model.RemoteAddress
import akka.testkit.TestProbe
import com.dhpcs.jsonrpc.JsonRpcMessage.{CorrelationId, NumericCorrelationId}
import com.dhpcs.jsonrpc.{JsonRpcNotificationMessage, JsonRpcResponseSuccessMessage}
import com.dhpcs.liquidity.actor.protocol.{
  AuthenticatedCommandWithIds,
  EnvelopedAuthenticatedCommandWithIds,
  SuccessResponseWithIds
}
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.server.InMemPersistenceTestFixtures
import com.dhpcs.liquidity.server.actor.ClientConnectionActor.WrappedJsonRpcMessage
import com.dhpcs.liquidity.ws.protocol._
import org.scalatest.OptionValues._
import org.scalatest.{Inside, Outcome, fixture}

import scala.concurrent.duration._

class ClientConnectionActorSpec extends fixture.FreeSpec with InMemPersistenceTestFixtures with Inside {

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
      ClientConnectionActor
        .props(ip, publicKey, zoneValidatorShardRegionTestProbe.ref, keepAliveInterval = 3.seconds)(
          upstreamTestProbe.ref)
        .withDeploy(Deploy.local)
    )
    sinkTestProbe.send(clientConnection, ClientConnectionActor.ActorSinkInit)
    sinkTestProbe.expectMsg(ClientConnectionActor.ActorSinkAck)
    try withFixture(
      test.toNoArgTest((sinkTestProbe, zoneValidatorShardRegionTestProbe, upstreamTestProbe, clientConnection))
    )
    finally system.stop(clientConnection)
  }

  "A ClientConnectionActor" - {
    "will send a SupportedVersionsNotification when connected" in { fixture =>
      val (_, _, upstreamTestProbe, _) = fixture
      expectNotification(upstreamTestProbe) === SupportedVersionsNotification(CompatibleVersionNumbers)
    }
    "will send a KeepAliveNotification when left idle" in { fixture =>
      val (_, _, upstreamTestProbe, _) = fixture
      expectNotification(upstreamTestProbe) === SupportedVersionsNotification(CompatibleVersionNumbers)
      upstreamTestProbe.within(3.5.seconds)(
        expectNotification(upstreamTestProbe) === KeepAliveNotification
      )
    }
    "will reply with a CreateZoneResponse when forwarding a CreateZoneCommand" in { fixture =>
      val (sinkTestProbe, zoneValidatorShardRegionTestProbe, upstreamTestProbe, clientConnection) = fixture
      expectNotification(upstreamTestProbe) === SupportedVersionsNotification(CompatibleVersionNumbers)
      val command = CreateZoneCommand(
        equityOwnerPublicKey = publicKey,
        equityOwnerName = Some("Dave"),
        equityOwnerMetadata = None,
        equityAccountName = None,
        equityAccountMetadata = None,
        name = Some("Dave's Game")
      )
      val correlationId = NumericCorrelationId(0)
      send(sinkTestProbe, clientConnection)(command, correlationId)
      val zoneId = inside(zoneValidatorShardRegionTestProbe.expectMsgType[EnvelopedAuthenticatedCommandWithIds]) {
        case envelopedMessage @ EnvelopedAuthenticatedCommandWithIds(_, authenticatedCommandWithIds) =>
          authenticatedCommandWithIds ===
            AuthenticatedCommandWithIds(publicKey, command, correlationId, sequenceNumber = 1L, deliveryId = 1L)
          envelopedMessage.zoneId
      }
      val result = CreateZoneResponse({
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
        SuccessResponseWithIds(result, correlationId, sequenceNumber = 1L, deliveryId = 1L)
      )
      expectResponse(upstreamTestProbe, "createZone") === result
    }
  }

  private[this] def send(sinkTestProbe: TestProbe, clientConnection: ActorRef)(command: Command,
                                                                               correlationId: CorrelationId): Unit = {
    sinkTestProbe.send(
      clientConnection,
      WrappedJsonRpcMessage(Command.write(command, id = correlationId))
    )
    sinkTestProbe.expectMsg(ClientConnectionActor.ActorSinkAck)
  }

  private[this] def expectNotification(upstreamTestProbe: TestProbe): Notification = {
    val notification = upstreamTestProbe.expectMsgPF() {
      case WrappedJsonRpcMessage(jsonRpcNotificationMessage: JsonRpcNotificationMessage) =>
        Notification.read(jsonRpcNotificationMessage)
    }
    notification.asOpt.value
  }

  private[this] def expectResponse(upstreamTestProbe: TestProbe, method: String): Response = {
    val response = upstreamTestProbe.expectMsgPF() {
      case WrappedJsonRpcMessage(jsonRpcResponseSuccessMessage: JsonRpcResponseSuccessMessage) =>
        Response.read(jsonRpcResponseSuccessMessage, method)
    }
    response.asOpt.value
  }
}
