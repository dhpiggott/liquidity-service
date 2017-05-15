package com.dhpcs.liquidity.server.actor

import java.net.InetAddress
import java.security.KeyPairGenerator

import akka.actor.{ActorRef, Deploy}
import akka.http.scaladsl.model.RemoteAddress
import akka.testkit.TestProbe
import com.dhpcs.jsonrpc.JsonRpcMessage.NumericCorrelationId
import com.dhpcs.jsonrpc.{JsonRpcNotificationMessage, JsonRpcResponseSuccessMessage}
import com.dhpcs.liquidity.actor.protocol.{AuthenticatedZoneCommandWithIds, ZoneResponseWithIds, ZoneValidatorMessage}
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.serialization.ProtoConverter
import com.dhpcs.liquidity.server.InMemPersistenceTestFixtures
import com.dhpcs.liquidity.server.actor.LegacyClientConnectionActor._
import com.dhpcs.liquidity.ws.protocol.legacy._
import org.scalatest.OptionValues._
import org.scalatest.{Inside, Outcome, fixture}

import scala.concurrent.duration._

class LegacyClientConnectionActorSpec extends fixture.FreeSpec with InMemPersistenceTestFixtures with Inside {

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
      LegacyClientConnectionActor
        .props(ip, publicKey, zoneValidatorShardRegionTestProbe.ref, keepAliveInterval = 3.seconds)(
          upstreamTestProbe.ref)
        .withDeploy(Deploy.local)
    )
    sinkTestProbe.send(clientConnection, LegacyClientConnectionActor.ActorSinkInit)
    sinkTestProbe.expectMsg(LegacyClientConnectionActor.ActorSinkAck)
    try withFixture(
      test.toNoArgTest((sinkTestProbe, zoneValidatorShardRegionTestProbe, upstreamTestProbe, clientConnection))
    )
    finally system.stop(clientConnection)
  }

  "A LegacyClientConnectionActor" - {
    "will send a JSON-RPC SupportedVersionsNotification when connected" in { fixture =>
      val (_, _, upstreamTestProbe, _) = fixture
      assert(expectJsonRpcNotification(upstreamTestProbe) === SupportedVersionsNotification(CompatibleVersionNumbers))
    }
    "will send a JSON-RPC KeepAliveNotification when left idle" in { fixture =>
      val (_, _, upstreamTestProbe, _) = fixture
      assert(expectJsonRpcNotification(upstreamTestProbe) === SupportedVersionsNotification(CompatibleVersionNumbers))
      upstreamTestProbe.within(3.5.seconds)(
        assert(expectJsonRpcNotification(upstreamTestProbe) === KeepAliveNotification)
      )
    }
    "will reply with a JSON-RPC CreateZoneResponse when forwarding a JSON-RPC CreateZoneCommand" in { fixture =>
      val (sinkTestProbe, zoneValidatorShardRegionTestProbe, upstreamTestProbe, clientConnection) = fixture
      assert(expectJsonRpcNotification(upstreamTestProbe) === SupportedVersionsNotification(CompatibleVersionNumbers))
      val command = CreateZoneCommand(
        equityOwnerPublicKey = publicKey,
        equityOwnerName = Some("Dave"),
        equityOwnerMetadata = None,
        equityAccountName = None,
        equityAccountMetadata = None,
        name = Some("Dave's Game")
      )
      val correlationId = 0L
      sendJsonRpc(sinkTestProbe, clientConnection)(command, correlationId)
      val zoneId = inside(zoneValidatorShardRegionTestProbe.expectMsgType[AuthenticatedZoneCommandWithIds]) {
        case AuthenticatedZoneCommandWithIds(`publicKey`, zoneCommand, `correlationId`, 1L, 1L) =>
          zoneCommand.zoneId
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
        // asProto perhaps isn't the best name; we're just converting to the ZoneValidatorActor protocol equivalent.
        ZoneResponseWithIds(ProtoConverter[ZoneResponse, ZoneValidatorMessage.ZoneResponse].asProto(result),
                            correlationId,
                            sequenceNumber = 1L,
                            deliveryId = 1L)
      )
      assert(expectJsonRpcResponse(upstreamTestProbe, "createZone") === result)
    }
  }

  private[this] def expectJsonRpcNotification(upstreamTestProbe: TestProbe): Notification = {
    val notification = upstreamTestProbe.expectMsgPF() {
      case WrappedJsonRpcNotification(jsonRpcNotificationMessage: JsonRpcNotificationMessage) =>
        Notification.read(jsonRpcNotificationMessage)
    }
    notification.asOpt.value
  }

  private[this] def sendJsonRpc(sinkTestProbe: TestProbe, clientConnection: ActorRef)(command: Command,
                                                                                      correlationId: Long): Unit = {
    sinkTestProbe.send(
      clientConnection,
      WrappedJsonRpcRequest(Command.write(command, id = NumericCorrelationId(correlationId)))
    )
    sinkTestProbe.expectMsg(LegacyClientConnectionActor.ActorSinkAck)
  }

  private[this] def expectJsonRpcResponse(upstreamTestProbe: TestProbe, method: String): Response = {
    val response = upstreamTestProbe.expectMsgPF() {
      case WrappedJsonRpcResponse(jsonRpcResponseSuccessMessage: JsonRpcResponseSuccessMessage) =>
        SuccessResponse.read(jsonRpcResponseSuccessMessage, method)
    }
    response.asOpt.value
  }
}
