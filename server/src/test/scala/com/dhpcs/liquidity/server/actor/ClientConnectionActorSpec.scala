package com.dhpcs.liquidity.server.actor

import java.net.InetAddress

import akka.actor.ActorRef
import akka.http.scaladsl.model.RemoteAddress
import akka.testkit.TestProbe
import com.dhpcs.liquidity.actor.protocol._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.server.InMemPersistenceTestFixtures
import com.dhpcs.liquidity.ws.protocol._
import org.scalatest.{Inside, Outcome, fixture}

import scala.concurrent.duration._

class ClientConnectionActorSpec extends fixture.FreeSpec with InMemPersistenceTestFixtures with Inside {

  private[this] val publicKey = PublicKey(ModelSpec.rsaPublicKey.getEncoded)
  private[this] val ip        = RemoteAddress(InetAddress.getLoopbackAddress)

  override protected type FixtureParam = (TestProbe, TestProbe, TestProbe, ActorRef)

  override protected def withFixture(test: OneArgTest): Outcome = {
    val sinkTestProbe                     = TestProbe()
    val zoneValidatorShardRegionTestProbe = TestProbe()
    val upstreamTestProbe                 = TestProbe()
    val clientConnection = system.actorOf(
      ClientConnectionActor
        .props(ip, zoneValidatorShardRegionTestProbe.ref, pingInterval = 3.seconds)(upstreamTestProbe.ref)
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
        proto.ws.protocol.ServerMessage.Message.KeyOwnershipProof(
          createKeyOwnershipProof(ModelSpec.rsaPublicKey, ModelSpec.rsaPrivateKey, keyOwnershipChallenge))
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
      val envelopedZoneCommand =
        zoneValidatorShardRegionTestProbe.expectMsgType[EnvelopedZoneCommand]
      assert(envelopedZoneCommand.publicKey === publicKey)
      assert(envelopedZoneCommand.correlationId === correlationId)
      assert(envelopedZoneCommand.sequenceNumber === 1L)
      assert(envelopedZoneCommand.deliveryId === 1L)
      val zoneId = envelopedZoneCommand.zoneId
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
        EnvelopedZoneResponse(result, correlationId, sequenceNumber = 1L, deliveryId = 1L)
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
          ProtoBinding[CreateZoneCommand, proto.actor.protocol.ZoneCommand.CreateZoneCommand]
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
            ProtoBinding[ZoneResponse, proto.actor.protocol.ZoneResponse.ZoneResponse]
              .asScala(protoZoneResponse.zoneResponse)
        }
    }

  private[this] def expectMessage(upstreamTestProbe: TestProbe): proto.ws.protocol.ClientMessage.Message =
    upstreamTestProbe.expectMsgType[proto.ws.protocol.ClientMessage].message

}
