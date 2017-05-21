package com.dhpcs.liquidity.server.actor

import java.net.InetAddress
import java.security.KeyPairGenerator

import akka.actor.{ActorRef, Deploy}
import akka.http.scaladsl.model.RemoteAddress
import akka.testkit.TestProbe
import com.dhpcs.liquidity.actor.protocol._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.serialization.ProtoConverter
import com.dhpcs.liquidity.server.InMemPersistenceTestFixtures
import com.dhpcs.liquidity.ws.protocol._
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
        .props(ip, publicKey, zoneValidatorShardRegionTestProbe.ref, pingInterval = 3.seconds)(upstreamTestProbe.ref)
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
    "will reply with a CreateZoneResponse when forwarding a Protobuf CreateZoneCommand" in { fixture =>
      val (sinkTestProbe, zoneValidatorShardRegionTestProbe, upstreamTestProbe, clientConnection) = fixture
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
      assert(expectServerResponse(upstreamTestProbe) === result)
    }
  }

  private[this] def expectClientCommand(upstreamTestProbe: TestProbe): ClientCommand =
    inside(upstreamTestProbe.expectMsgType[proto.ws.protocol.ClientMessage].commandOrResponseOrNotification) {
      case proto.ws.protocol.ClientMessage.CommandOrResponseOrNotification.Command(protoCommand) =>
        inside(protoCommand.command) {
          case proto.ws.protocol.ClientMessage.Command.Command.PingCommand(protoPingCommand) =>
            ProtoConverter[PingCommand.type, proto.ws.protocol.PingCommand].asScala(protoPingCommand)
        }
    }

  private[this] def sendServerCommand(sinkTestProbe: TestProbe, clientConnection: ActorRef)(
      serverCommand: ServerCommand,
      correlationId: Long): Unit = {
    sinkTestProbe.send(
      clientConnection,
      proto.ws.protocol.ServerMessage(
        proto.ws.protocol.ServerMessage.CommandOrResponse.Command(proto.ws.protocol.ServerMessage.Command(
          correlationId,
          serverCommand match {
            case zoneCommand: ZoneCommand =>
              proto.ws.protocol.ServerMessage.Command.Command.ZoneCommand(
                proto.ws.protocol.ZoneCommand(
                  ProtoConverter[ZoneCommand, proto.ws.protocol.ZoneCommand.ZoneCommand]
                    .asProto(zoneCommand)
                ))
          }
        )))
    )
    sinkTestProbe.expectMsg(ClientConnectionActor.ActorSinkAck)
  }

  private[this] def expectServerResponse(upstreamTestProbe: TestProbe): ServerResponse =
    inside(upstreamTestProbe.expectMsgType[proto.ws.protocol.ClientMessage].commandOrResponseOrNotification) {
      case proto.ws.protocol.ClientMessage.CommandOrResponseOrNotification.Response(protoResponse) =>
        inside(protoResponse.response) {
          case proto.ws.protocol.ClientMessage.Response.Response.ZoneResponse(protoZoneResponse) =>
            ProtoConverter[ZoneResponse, proto.ws.protocol.ZoneResponse.ZoneResponse]
              .asScala(protoZoneResponse.zoneResponse)
        }
    }

}
