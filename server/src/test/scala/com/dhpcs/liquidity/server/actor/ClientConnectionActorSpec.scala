package com.dhpcs.liquidity.server.actor

import java.net.InetAddress

import akka.actor.{ActorRef, Deploy}
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
    "will send a PingCommand when left idle" in { fixture =>
      val (_, _, upstreamTestProbe, _) = fixture
      upstreamTestProbe.within(3.5.seconds)(
        assert(expectClientCommand(upstreamTestProbe) === PingCommand)
      )
    }
    "will reply with a CreateZoneResponse when forwarding a CreateZoneCommand" in { fixture =>
      val (sinkTestProbe, zoneValidatorShardRegionTestProbe, upstreamTestProbe, clientConnection) = fixture
      sendMessage(sinkTestProbe, clientConnection)(
        proto.ws.protocol.ServerMessage.Message
          .BeginKeyOwnershipProof(createBeginKeyOwnershipProofMessage(ModelSpec.rsaPublicKey))
      )
      val keyOwnershipProofNonce = inside(expectMessage(upstreamTestProbe)) {
        case proto.ws.protocol.ClientMessage.Message.KeyOwnershipProofNonce(protoKeyOwnershipProofNonce) =>
          protoKeyOwnershipProofNonce
      }
      sendMessage(sinkTestProbe, clientConnection)(
        proto.ws.protocol.ServerMessage.Message.CompleteKeyOwnershipProof(
          createCompleteKeyOwnershipProofMessage(ModelSpec.rsaPrivateKey, keyOwnershipProofNonce))
      )
      val command = CreateZoneCommand(
        equityOwnerPublicKey = publicKey,
        equityOwnerName = Some("Dave"),
        equityOwnerMetadata = None,
        equityAccountName = None,
        equityAccountMetadata = None,
        name = Some("Dave's Game")
      )
      val correlationId = 0L
      sendServerCommand(sinkTestProbe, clientConnection)(command, correlationId)
      val authenticatedZoneCommandWithIds =
        zoneValidatorShardRegionTestProbe.expectMsgType[AuthenticatedZoneCommandWithIds]
      assert(authenticatedZoneCommandWithIds.publicKey === publicKey)
      assert(authenticatedZoneCommandWithIds.correlationId === correlationId)
      assert(authenticatedZoneCommandWithIds.sequenceNumber === 1L)
      assert(authenticatedZoneCommandWithIds.deliveryId === 1L)
      val zoneId = authenticatedZoneCommandWithIds.command.zoneId
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
        ZoneResponseWithIds(ProtoBinding[ZoneResponse, ZoneValidatorMessage.ZoneResponse].asProto(result),
                            correlationId,
                            sequenceNumber = 1L,
                            deliveryId = 1L)
      )
      assert(expectServerResponse(upstreamTestProbe) === result)
    }
  }

  private[this] def expectClientCommand(upstreamTestProbe: TestProbe): ClientCommand =
    inside(upstreamTestProbe.expectMsgType[proto.ws.protocol.ClientMessage].message) {
      case proto.ws.protocol.ClientMessage.Message.Command(protoCommand) =>
        inside(protoCommand.command) {
          case proto.ws.protocol.ClientMessage.Command.Command.PingCommand(_) =>
            PingCommand
        }
    }

  private[this] def sendServerCommand(sinkTestProbe: TestProbe, clientConnection: ActorRef)(
      serverCommand: ServerCommand,
      correlationId: Long): Unit =
    sendMessage(sinkTestProbe, clientConnection)(
      proto.ws.protocol.ServerMessage.Message.Command(proto.ws.protocol.ServerMessage.Command(
        correlationId,
        serverCommand match {
          case zoneCommand: ZoneCommand =>
            proto.ws.protocol.ServerMessage.Command.Command.ZoneCommand(
              proto.ws.protocol.ZoneCommand(
                ProtoBinding[ZoneCommand, proto.ws.protocol.ZoneCommand.ZoneCommand]
                  .asProto(zoneCommand)
              ))
        }
      ))
    )

  private[this] def sendMessage(sinkTestProbe: TestProbe, clientConnection: ActorRef)(
      message: proto.ws.protocol.ServerMessage.Message): Unit = {
    sinkTestProbe.send(
      clientConnection,
      proto.ws.protocol.ServerMessage(message)
    )
    sinkTestProbe.expectMsg(ClientConnectionActor.ActorSinkAck); ()
  }

  private[this] def expectServerResponse(upstreamTestProbe: TestProbe): ServerResponse =
    inside(expectMessage(upstreamTestProbe)) {
      case proto.ws.protocol.ClientMessage.Message.Response(protoResponse) =>
        inside(protoResponse.response) {
          case proto.ws.protocol.ClientMessage.Response.Response.ZoneResponse(protoZoneResponse) =>
            ProtoBinding[ZoneResponse, proto.ws.protocol.ZoneResponse.ZoneResponse]
              .asScala(protoZoneResponse.zoneResponse)
        }
    }

  private[this] def expectMessage(upstreamTestProbe: TestProbe): proto.ws.protocol.ClientMessage.Message =
    upstreamTestProbe.expectMsgType[proto.ws.protocol.ClientMessage].message

}
