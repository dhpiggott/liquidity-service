package com.dhpcs.liquidity.server.actor

import java.net.InetAddress
import java.security.KeyPairGenerator

import akka.actor.{ActorRef, Deploy}
import akka.http.scaladsl.model.RemoteAddress
import akka.testkit.TestProbe
import com.dhpcs.liquidity.actor.protocol.{AuthenticatedZoneCommandWithIds, ZoneResponseWithIds, ZoneValidatorMessage}
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.serialization.ProtoConverter
import com.dhpcs.liquidity.server.InMemPersistenceTestFixtures
import com.dhpcs.liquidity.server.actor.ClientConnectionActor._
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
    "will send a JSON-RPC KeepAliveNotification when left idle" in { fixture =>
      val (_, _, upstreamTestProbe, _) = fixture
      upstreamTestProbe.within(3.5.seconds)(
        assert(expectNotification(upstreamTestProbe) === KeepAliveNotification)
      )
    }
    "will reply with a Protobuf CreateZoneResponse when forwarding a Protobuf CreateZoneCommand" in { fixture =>
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
      sendCommand(sinkTestProbe, clientConnection)(command, correlationId)
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
      assert(expectResponse(upstreamTestProbe) === result)
    }
  }

  private[this] def sendCommand(sinkTestProbe: TestProbe, clientConnection: ActorRef)(command: Command,
                                                                                      correlationId: Long): Unit = {
    sinkTestProbe.send(
      clientConnection,
      WrappedProtobufCommand(
        proto.ws.protocol.Command(
          correlationId,
          command match {
            case zoneCommand: ZoneCommand =>
              proto.ws.protocol.Command.Command.ZoneCommand(
                proto.ws.protocol.ZoneCommand(ProtoConverter[ZoneCommand, proto.ws.protocol.ZoneCommand.ZoneCommand]
                  .asProto(zoneCommand))
              )
          }
        )
      )
    )
    sinkTestProbe.expectMsg(ClientConnectionActor.ActorSinkAck)
  }

  private[this] def expectNotification(upstreamTestProbe: TestProbe): Notification =
    upstreamTestProbe.expectMsgPF() {
      case WrappedProtobufNotification(protoNotification) =>
        protoNotification.notification match {
          case proto.ws.protocol.ResponseOrNotification.Notification.Notification.Empty =>
            sys.error("Empty")
          case proto.ws.protocol.ResponseOrNotification.Notification.Notification.KeepAliveNotification(_) =>
            KeepAliveNotification
          case proto.ws.protocol.ResponseOrNotification.Notification.Notification
                .ZoneNotification(protoZoneNotification) =>
            ProtoConverter[ZoneNotification, proto.ws.protocol.ZoneNotification.ZoneNotification]
              .asScala(protoZoneNotification.zoneNotification)
        }
    }

  private[this] def expectResponse(upstreamTestProbe: TestProbe): Response =
    upstreamTestProbe.expectMsgPF() {
      case WrappedProtobufResponse(protoResponse) =>
        protoResponse.response match {
          case proto.ws.protocol.ResponseOrNotification.Response.Response.Empty =>
            sys.error("Empty")
          case proto.ws.protocol.ResponseOrNotification.Response.Response.ZoneResponse(protoZoneResponse) =>
            ProtoConverter[ZoneResponse, proto.ws.protocol.ZoneResponse.ZoneResponse]
              .asScala(protoZoneResponse.zoneResponse)
        }
    }

}
