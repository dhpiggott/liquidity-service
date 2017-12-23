package com.dhpcs.liquidity.server.actor

import java.net.InetAddress

import akka.actor.ActorRef
import akka.testkit.TestProbe
import akka.typed.scaladsl.adapter._
import cats.data.Validated
import com.dhpcs.liquidity.actor.protocol.clientconnection._
import com.dhpcs.liquidity.actor.protocol.zonevalidator._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.server.InmemoryPersistenceTestFixtures
import com.dhpcs.liquidity.testkit.TestKit
import com.dhpcs.liquidity.ws.protocol.ProtoBindings._
import com.dhpcs.liquidity.ws.protocol._
import org.scalatest.{Inside, Outcome, fixture}

import scala.concurrent.duration._

class ClientConnectionActorSpec extends fixture.FreeSpec with InmemoryPersistenceTestFixtures with Inside {

  private[this] val publicKey = PublicKey(TestKit.rsaPublicKey.getEncoded)

  override protected type FixtureParam = (TestProbe, TestProbe, TestProbe, ActorRef)

  override protected def withFixture(test: OneArgTest): Outcome = {
    val sinkTestProbe                     = TestProbe()
    val zoneValidatorShardRegionTestProbe = TestProbe()
    val webSocketOutTestProbe             = TestProbe()
    val clientConnection = system.spawnAnonymous(
      ClientConnectionActor.behavior(pingInterval = 3.seconds,
                                     zoneValidatorShardRegionTestProbe.ref,
                                     InetAddress.getLoopbackAddress)(webSocketOutTestProbe.ref)
    )
    sinkTestProbe.send(clientConnection.toUntyped, InitActorSink(sinkTestProbe.ref))
    sinkTestProbe.expectMsg(ActorSinkAck)
    try withFixture(
      test.toNoArgTest(
        (sinkTestProbe, zoneValidatorShardRegionTestProbe, webSocketOutTestProbe, clientConnection.toUntyped))
    )
    finally system.stop(clientConnection.toUntyped)
  }

  "A ClientConnectionActor" - {
    "sends a PingCommand when left idle" in { fixture =>
      val (_, _, webSocketOutTestProbe, _) = fixture
      inside(expectMessage(webSocketOutTestProbe)) {
        case proto.ws.protocol.ClientMessage.Message.KeyOwnershipChallenge(_) => ()
      }
      webSocketOutTestProbe.within(3.5.seconds)(
        assert(
          expectClientCommand(webSocketOutTestProbe) === proto.ws.protocol.ClientMessage.Command.Command
            .PingCommand(com.google.protobuf.ByteString.EMPTY))
      )
    }
    "replies with a CreateZoneResponse when forwarding a CreateZoneCommand" in { fixture =>
      val (sinkTestProbe, zoneValidatorShardRegionTestProbe, webSocketOutTestProbe, clientConnection) = fixture
      val keyOwnershipChallenge = inside(expectMessage(webSocketOutTestProbe)) {
        case keyOwnershipChallenge: proto.ws.protocol.ClientMessage.Message.KeyOwnershipChallenge =>
          keyOwnershipChallenge.value
      }
      sendMessage(sinkTestProbe, clientConnection)(
        proto.ws.protocol.ServerMessage.Message.KeyOwnershipProof(Authentication
          .createKeyOwnershipProof(TestKit.rsaPublicKey, TestKit.rsaPrivateKey, keyOwnershipChallenge))
      )
      val createZoneCommand = CreateZoneCommand(
        equityOwnerPublicKey = publicKey,
        equityOwnerName = Some("Dave"),
        equityOwnerMetadata = None,
        equityAccountName = None,
        equityAccountMetadata = None,
        name = Some("Dave's Game")
      )
      val correlationId = 0L
      sendCreateZoneCommand(sinkTestProbe, clientConnection)(createZoneCommand, correlationId)
      val zoneCommandEnvelope =
        zoneValidatorShardRegionTestProbe.expectMsgType[ZoneCommandEnvelope]
      assert(zoneCommandEnvelope.publicKey === publicKey)
      assert(zoneCommandEnvelope.correlationId === correlationId)
      val zoneId = zoneCommandEnvelope.zoneId
      val result = CreateZoneResponse({
        val created              = System.currentTimeMillis
        val equityAccountId      = AccountId(0.toString)
        val equityAccountOwnerId = MemberId(0.toString)
        Validated.valid(
          Zone(
            id = zoneId,
            equityAccountId,
            members = Map(equityAccountOwnerId -> Member(equityAccountOwnerId, Set(publicKey), name = Some("Dave"))),
            accounts = Map(equityAccountId     -> Account(equityAccountId, ownerMemberIds = Set(equityAccountOwnerId))),
            transactions = Map.empty,
            created = created,
            expires = created + 2.days.toMillis,
            name = Some("Dave's Game"),
            metadata = None
          ))
      })
      zoneValidatorShardRegionTestProbe.send(
        clientConnection,
        ZoneResponseEnvelope(zoneValidatorShardRegionTestProbe.ref, correlationId, result)
      )
      assert(expectZoneResponse(webSocketOutTestProbe) === result)
    }
  }

  private[this] def expectClientCommand(
      webSocketOutTestProbe: TestProbe): proto.ws.protocol.ClientMessage.Command.Command =
    inside(webSocketOutTestProbe.expectMsgType[proto.ws.protocol.ClientMessage].message) {
      case proto.ws.protocol.ClientMessage.Message.Command(proto.ws.protocol.ClientMessage.Command(_, protoCommand)) =>
        protoCommand
    }

  private[this] def sendCreateZoneCommand(sinkTestProbe: TestProbe, clientConnection: ActorRef)(
      createZoneCommand: CreateZoneCommand,
      correlationId: Long): Unit =
    sendMessage(sinkTestProbe, clientConnection)(
      proto.ws.protocol.ServerMessage.Message.Command(proto.ws.protocol.ServerMessage.Command(
        correlationId,
        proto.ws.protocol.ServerMessage.Command.Command.CreateZoneCommand(
          ProtoBinding[CreateZoneCommand, proto.ws.protocol.ZoneCommand.CreateZoneCommand, Any]
            .asProto(createZoneCommand)(()))
      )))

  private[this] def sendMessage(sinkTestProbe: TestProbe, clientConnection: ActorRef)(
      message: proto.ws.protocol.ServerMessage.Message): Unit = {
    sinkTestProbe.send(
      clientConnection,
      ActorFlowServerMessage(sinkTestProbe.ref, proto.ws.protocol.ServerMessage(message))
    )
    sinkTestProbe.expectMsg(ActorSinkAck); ()
  }

  private[this] def expectZoneResponse(webSocketOutTestProbe: TestProbe): ZoneResponse =
    inside(expectMessage(webSocketOutTestProbe)) {
      case proto.ws.protocol.ClientMessage.Message.Response(protoResponse) =>
        inside(protoResponse.response) {
          case proto.ws.protocol.ClientMessage.Response.Response.ZoneResponse(protoZoneResponse) =>
            ProtoBinding[ZoneResponse, proto.ws.protocol.ZoneResponse, Any].asScala(protoZoneResponse)(())
        }
    }

  private[this] def expectMessage(webSocketOutTestProbe: TestProbe): proto.ws.protocol.ClientMessage.Message =
    webSocketOutTestProbe.expectMsgType[proto.ws.protocol.ClientMessage].message

}
