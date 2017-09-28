package com.dhpcs.liquidity.server.actor

import java.net.InetAddress

import akka.actor.ActorRef
import akka.testkit.TestProbe
import cats.data.Validated
import com.dhpcs.liquidity.ws.protocol.ProtoBindings._
import com.dhpcs.liquidity.actor.protocol.zonevalidator._
import com.dhpcs.liquidity.model.ProtoBindings._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.server.InmemoryPersistenceTestFixtures
import com.dhpcs.liquidity.testkit.TestKit
import com.dhpcs.liquidity.ws.protocol._
import org.scalatest.{Inside, Outcome, fixture}

import scala.concurrent.duration._

class ClientConnectionActorSpec extends fixture.FreeSpec with InmemoryPersistenceTestFixtures with Inside {

  private[this] val publicKey = PublicKey(TestKit.rsaPublicKey.getEncoded)

  override protected type FixtureParam = (TestProbe, TestProbe, TestProbe, ActorRef)

  override protected def withFixture(test: OneArgTest): Outcome = {
    val sinkTestProbe                     = TestProbe()
    val zoneValidatorShardRegionTestProbe = TestProbe()
    val upstreamTestProbe                 = TestProbe()
    val clientConnection = system.actorOf(
      ClientConnectionActor.props(InetAddress.getLoopbackAddress,
                                  zoneValidatorShardRegionTestProbe.ref,
                                  pingInterval = 3.seconds)(upstreamTestProbe.ref)
    )
    sinkTestProbe.send(clientConnection, ClientConnectionActor.ActorSinkInit)
    sinkTestProbe.expectMsg(ClientConnectionActor.ActorSinkAck)
    try withFixture(
      test.toNoArgTest((sinkTestProbe, zoneValidatorShardRegionTestProbe, upstreamTestProbe, clientConnection))
    )
    finally system.stop(clientConnection)
  }

  "A ClientConnectionActor" - {
    "will send a PingCommand when left idle" in { fixture =>
      val (_, _, upstreamTestProbe, _) = fixture
      inside(expectMessage(upstreamTestProbe)) {
        case proto.ws.protocol.ClientMessage.Message.KeyOwnershipChallenge(_) => ()
      }
      upstreamTestProbe.within(3.5.seconds)(
        assert(
          expectClientCommand(upstreamTestProbe) === proto.ws.protocol.ClientMessage.Command.Command
            .PingCommand(com.google.protobuf.ByteString.EMPTY))
      )
    }
    "will reply with a CreateZoneResponse when forwarding a CreateZoneCommand" in { fixture =>
      val (sinkTestProbe, zoneValidatorShardRegionTestProbe, upstreamTestProbe, clientConnection) = fixture
      val keyOwnershipChallenge = inside(expectMessage(upstreamTestProbe)) {
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
      assert(zoneCommandEnvelope.sequenceNumber === 1L)
      assert(zoneCommandEnvelope.deliveryId === 1L)
      val zoneId = zoneCommandEnvelope.zoneId
      val result = CreateZoneResponse({
        val created = System.currentTimeMillis
        Validated.valid(
          Zone(
            id = zoneId,
            equityAccountId = AccountId("0"),
            members = Map(MemberId("0")   -> Member(MemberId("0"), Set(publicKey), name = Some("Dave"))),
            accounts = Map(AccountId("0") -> Account(AccountId("0"), ownerMemberIds = Set(MemberId("0")))),
            transactions = Map.empty,
            created = created,
            expires = created + 2.days.toMillis,
            name = Some("Dave's Game"),
            metadata = None
          ))
      })
      zoneValidatorShardRegionTestProbe.send(
        clientConnection,
        ZoneResponseEnvelope(correlationId, sequenceNumber = 1L, deliveryId = 1L, result)
      )
      assert(expectZoneResponse(upstreamTestProbe) === result)
    }
  }

  private[this] def expectClientCommand(upstreamTestProbe: TestProbe): proto.ws.protocol.ClientMessage.Command.Command =
    inside(upstreamTestProbe.expectMsgType[proto.ws.protocol.ClientMessage].message) {
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
            .asProto(createZoneCommand))
      )))

  private[this] def sendMessage(sinkTestProbe: TestProbe, clientConnection: ActorRef)(
      message: proto.ws.protocol.ServerMessage.Message): Unit = {
    sinkTestProbe.send(
      clientConnection,
      proto.ws.protocol.ServerMessage(message)
    )
    sinkTestProbe.expectMsg(ClientConnectionActor.ActorSinkAck); ()
  }

  private[this] def expectZoneResponse(upstreamTestProbe: TestProbe): ZoneResponse =
    inside(expectMessage(upstreamTestProbe)) {
      case proto.ws.protocol.ClientMessage.Message.Response(protoResponse) =>
        inside(protoResponse.response) {
          case proto.ws.protocol.ClientMessage.Response.Response.ZoneResponse(protoZoneResponse) =>
            ProtoBinding[ZoneResponse, proto.ws.protocol.ZoneResponse.ZoneResponse, Any]
              .asScala(protoZoneResponse.zoneResponse)(())
        }
    }

  private[this] def expectMessage(upstreamTestProbe: TestProbe): proto.ws.protocol.ClientMessage.Message =
    upstreamTestProbe.expectMsgType[proto.ws.protocol.ClientMessage].message

}
