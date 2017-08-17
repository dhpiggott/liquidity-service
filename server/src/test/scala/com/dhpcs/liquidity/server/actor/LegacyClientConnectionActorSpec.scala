package com.dhpcs.liquidity.server.actor

import java.net.InetAddress

import akka.actor.ActorRef
import akka.testkit.TestProbe
import cats.data.Validated
import com.dhpcs.jsonrpc.JsonRpcMessage.NumericCorrelationId
import com.dhpcs.jsonrpc.JsonRpcResponseSuccessMessage
import com.dhpcs.liquidity.actor.protocol.zonevalidator._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.server.InmemoryPersistenceTestFixtures
import com.dhpcs.liquidity.server.actor.LegacyClientConnectionActor._
import com.dhpcs.liquidity.ws.protocol.AuthenticationSpec
import com.dhpcs.liquidity.ws.protocol.legacy._
import org.scalatest.OptionValues._
import org.scalatest.{Outcome, fixture}

import scala.concurrent.duration._

class LegacyClientConnectionActorSpec extends fixture.FreeSpec with InmemoryPersistenceTestFixtures {

  private[this] val publicKey = PublicKey(AuthenticationSpec.rsaPublicKey.getEncoded)

  override protected type FixtureParam = (TestProbe, TestProbe, TestProbe, ActorRef)

  override protected def withFixture(test: OneArgTest): Outcome = {
    val sinkTestProbe                     = TestProbe()
    val zoneValidatorShardRegionTestProbe = TestProbe()
    val upstreamTestProbe                 = TestProbe()
    val clientConnection = system.actorOf(
      LegacyClientConnectionActor.props(InetAddress.getLoopbackAddress,
                                        publicKey,
                                        zoneValidatorShardRegionTestProbe.ref,
                                        keepAliveInterval = 3.seconds)(upstreamTestProbe.ref)
    )
    sinkTestProbe.send(clientConnection, LegacyClientConnectionActor.ActorSinkInit)
    sinkTestProbe.expectMsg(LegacyClientConnectionActor.ActorSinkAck)
    try withFixture(
      test.toNoArgTest((sinkTestProbe, zoneValidatorShardRegionTestProbe, upstreamTestProbe, clientConnection))
    )
    finally system.stop(clientConnection)
  }

  "A LegacyClientConnectionActor" - {
    "will send a SupportedVersionsNotification when connected" in { fixture =>
      val (_, _, upstreamTestProbe, _) = fixture
      assert(
        expectNotification(upstreamTestProbe) === LegacyWsProtocol.SupportedVersionsNotification(
          LegacyWsProtocol.CompatibleVersionNumbers))
    }
    "will send a KeepAliveNotification when left idle" in { fixture =>
      val (_, _, upstreamTestProbe, _) = fixture
      assert(
        expectNotification(upstreamTestProbe) === LegacyWsProtocol.SupportedVersionsNotification(
          LegacyWsProtocol.CompatibleVersionNumbers))
      upstreamTestProbe.within(3.5.seconds)(
        assert(expectNotification(upstreamTestProbe) === LegacyWsProtocol.KeepAliveNotification)
      )
    }
    "will reply with a CreateZoneResponse when forwarding a CreateZoneCommand" in { fixture =>
      val (sinkTestProbe, zoneValidatorShardRegionTestProbe, upstreamTestProbe, clientConnection) = fixture
      assert(
        expectNotification(upstreamTestProbe) === LegacyWsProtocol.SupportedVersionsNotification(
          LegacyWsProtocol.CompatibleVersionNumbers))
      val command = LegacyWsProtocol.CreateZoneCommand(
        equityOwnerPublicKey = publicKey,
        equityOwnerName = Some("Dave"),
        equityOwnerMetadata = None,
        equityAccountName = None,
        equityAccountMetadata = None,
        name = Some("Dave's Game")
      )
      val correlationId = 0L
      sendCommand(sinkTestProbe, clientConnection)(command, correlationId)
      val zoneCommandEnvelope =
        zoneValidatorShardRegionTestProbe.expectMsgType[ZoneCommandEnvelope]
      assert(zoneCommandEnvelope.publicKey === publicKey)
      assert(zoneCommandEnvelope.correlationId === correlationId)
      assert(zoneCommandEnvelope.sequenceNumber === 1L)
      assert(zoneCommandEnvelope.deliveryId === 1L)
      val zoneId  = zoneCommandEnvelope.zoneId
      val created = System.currentTimeMillis
      val zone = Zone(
        id = zoneId,
        equityAccountId = AccountId("0"),
        members = Map(MemberId("0")   -> Member(MemberId("0"), Set(publicKey), name = Some("Dave"))),
        accounts = Map(AccountId("0") -> Account(AccountId("0"), ownerMemberIds = Set(MemberId("0")))),
        transactions = Map.empty,
        created = created,
        expires = created + 2.days.toMillis,
        name = Some("Dave's Game"),
        metadata = None
      )
      zoneValidatorShardRegionTestProbe.send(
        clientConnection,
        ZoneResponseEnvelope(correlationId,
                             sequenceNumber = 1L,
                             deliveryId = 1L,
                             CreateZoneResponse(Validated.valid(zone)))
      )
      assert(expectResponse(upstreamTestProbe, "createZone") === LegacyWsProtocol.CreateZoneResponse(zone))
    }
  }

  private[this] def expectNotification(upstreamTestProbe: TestProbe): LegacyWsProtocol.Notification =
    upstreamTestProbe.expectMsgPF() {
      case WrappedNotification(jsonRpcNotificationMessage) =>
        LegacyWsProtocol.Notification.read(jsonRpcNotificationMessage).asOpt.value
    }

  private[this] def sendCommand(sinkTestProbe: TestProbe, clientConnection: ActorRef)(command: LegacyWsProtocol.Command,
                                                                                      correlationId: Long): Unit = {
    sinkTestProbe.send(
      clientConnection,
      WrappedCommand(LegacyWsProtocol.Command.write(command, id = NumericCorrelationId(correlationId)))
    )
    sinkTestProbe.expectMsg(LegacyClientConnectionActor.ActorSinkAck); ()
  }

  private[this] def expectResponse(upstreamTestProbe: TestProbe, method: String): LegacyWsProtocol.Response =
    upstreamTestProbe.expectMsgPF() {
      case WrappedResponse(jsonRpcResponseSuccessMessage: JsonRpcResponseSuccessMessage) =>
        LegacyWsProtocol.SuccessResponse.read(jsonRpcResponseSuccessMessage, method).asOpt.value
    }

}
