package com.dhpcs.liquidity.server.actor

import java.net.InetAddress

import akka.actor.ActorRef
import akka.actor.typed.ActorRefResolver
import akka.actor.typed.scaladsl.adapter._
import akka.testkit.TestProbe
import cats.data.Validated
import com.dhpcs.liquidity.actor.protocol.clientconnection._
import com.dhpcs.liquidity.actor.protocol.zonevalidator._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.server.InmemoryPersistenceTestFixtures
import com.dhpcs.liquidity.ws.protocol.ProtoBindings._
import com.dhpcs.liquidity.ws.protocol._
import org.scalatest.{Inside, Outcome, fixture}

import scala.concurrent.duration._
import scala.util.Random

class ClientConnectionActorSpec
    extends fixture.FreeSpec
    with InmemoryPersistenceTestFixtures
    with Inside {

  private[this] val publicKey = PublicKey(rsaPublicKey.getEncoded)

  override protected type FixtureParam =
    (TestProbe, TestProbe, TestProbe, ActorRef)

  override protected def withFixture(test: OneArgTest): Outcome = {
    val sinkTestProbe = TestProbe()
    val zoneValidatorShardRegionTestProbe = TestProbe()
    val webSocketOutTestProbe = TestProbe()
    val clientConnection = system.spawnAnonymous(
      ClientConnectionActor.behavior(
        pingInterval = 3.seconds,
        zoneValidatorShardRegionTestProbe.ref,
        InetAddress.getLoopbackAddress)(webSocketOutTestProbe.ref)
    )
    sinkTestProbe.send(clientConnection.toUntyped,
                       InitActorSink(sinkTestProbe.ref))
    sinkTestProbe.expectMsg(ActorSinkAck)
    try withFixture(
      test.toNoArgTest(
        (sinkTestProbe,
         zoneValidatorShardRegionTestProbe,
         webSocketOutTestProbe,
         clientConnection.toUntyped))
    )
    finally system.stop(clientConnection.toUntyped)
  }

  "A ClientConnectionActor" - {
    "receiving a KeyOwnershipProof" - {
      "rejects it if the signature is invalid" in { fixture =>
        val (sinkTestProbe, _, webSocketOutTestProbe, clientConnection) =
          fixture
        inside(expectMessage(webSocketOutTestProbe)) {
          case proto.ws.protocol.ClientMessage.Message
                .KeyOwnershipChallenge(_) =>
            ()
        }
        val invalidSignature = new Array[Byte](256)
        Random.nextBytes(invalidSignature)
        val keyOwnershipProof =
          proto.ws.protocol.ServerMessage.KeyOwnershipProof(
            com.google.protobuf.ByteString
              .copyFrom(rsaPublicKey.getEncoded),
            com.google.protobuf.ByteString.copyFrom(invalidSignature)
          )
        sendMessage(sinkTestProbe, clientConnection)(
          proto.ws.protocol.ServerMessage.Message
            .KeyOwnershipProof(keyOwnershipProof)
        )
        webSocketOutTestProbe.expectNoMessage(3.5.seconds)
      }
      "accepts it if valid" in { fixture =>
        authenticate(fixture)
        receivePing(fixture)
      }
    }
    "receiving a CreateZoneCommand" - {
      "relays it and in turn relays the CreateZoneResponse" in { fixture =>
        authenticate(fixture)
        createZone(fixture)
      }
    }
    "receiving a JoinZoneCommand" - {
      "relays it and in turn relays the JoinZoneResponse and ClientJoinedNotification" in {
        fixture =>
          authenticate(fixture)
          val zone = createZone(fixture)
          joinZone(fixture, zone)
      }
    }
    "receiving a QuitZoneCommand" - {
      "relays it and in turn relays the QuitZoneResponse" in { fixture =>
        authenticate(fixture)
        val zone = createZone(fixture)
        joinZone(fixture, zone)
        quitZone(fixture, zone)
      }
    }
    "left idle" - {
      "sends a PingCommand" in { fixture =>
        val (_, _, webSocketOutTestProbe, _) = fixture
        inside(expectMessage(webSocketOutTestProbe)) {
          case proto.ws.protocol.ClientMessage.Message
                .KeyOwnershipChallenge(_) =>
            ()
        }
        receivePing(fixture)
      }
    }
  }

  private[this] def receivePing(fixture: FixtureParam): Unit = {
    val (_, _, webSocketOutTestProbe, _) = fixture
    webSocketOutTestProbe.within(3.5.seconds)(
      assert(
        expectClientCommand(webSocketOutTestProbe) === proto.ws.protocol.ClientMessage.Command.Command
          .PingCommand(com.google.protobuf.ByteString.EMPTY)
      )
    )
    ()
  }

  private[this] def authenticate(fixture: FixtureParam): Unit = {
    val (sinkTestProbe, _, webSocketOutTestProbe, clientConnection) = fixture
    val keyOwnershipChallenge = inside(expectMessage(webSocketOutTestProbe)) {
      case proto.ws.protocol.ClientMessage.Message
            .KeyOwnershipChallenge(value) =>
        value
    }
    sendMessage(sinkTestProbe, clientConnection)(
      proto.ws.protocol.ServerMessage.Message.KeyOwnershipProof(
        createKeyOwnershipProof(
          rsaPublicKey,
          rsaPrivateKey,
          keyOwnershipChallenge
        )
      )
    )
  }

  private[this] def createZone(fixture: FixtureParam): Zone = {
    val (sinkTestProbe,
         zoneValidatorShardRegionTestProbe,
         webSocketOutTestProbe,
         clientConnection) = fixture
    val createZoneCommand = CreateZoneCommand(
      equityOwnerPublicKey = publicKey,
      equityOwnerName = Some("Dave"),
      equityOwnerMetadata = None,
      equityAccountName = None,
      equityAccountMetadata = None,
      name = Some("Dave's Game"),
      metadata = None
    )
    val correlationId = 0L
    sendCreateZoneCommand(sinkTestProbe, clientConnection)(createZoneCommand,
                                                           correlationId)
    val zoneCommandEnvelope =
      zoneValidatorShardRegionTestProbe.expectMsgType[ZoneCommandEnvelope]
    assert(zoneCommandEnvelope.publicKey === publicKey)
    assert(zoneCommandEnvelope.correlationId === correlationId)
    val zoneId = zoneCommandEnvelope.zoneId
    val created = System.currentTimeMillis
    val equityAccountId = AccountId(0.toString)
    val equityAccountOwnerId = MemberId(0.toString)
    val zone = Zone(
      id = zoneId,
      equityAccountId,
      members = Map(
        equityAccountOwnerId -> Member(
          equityAccountOwnerId,
          ownerPublicKeys = Set(publicKey),
          name = Some("Dave"),
          metadata = None
        )
      ),
      accounts = Map(
        equityAccountId -> Account(
          equityAccountId,
          ownerMemberIds = Set(equityAccountOwnerId),
          name = None,
          metadata = None
        )
      ),
      transactions = Map.empty,
      created = created,
      expires = created + java.time.Duration.ofDays(30).toMillis,
      name = Some("Dave's Game"),
      metadata = None
    )
    val createZoneResponse = CreateZoneResponse(Validated.valid(zone))
    zoneValidatorShardRegionTestProbe.send(
      clientConnection,
      ZoneResponseEnvelope(zoneValidatorShardRegionTestProbe.ref,
                           correlationId,
                           createZoneResponse)
    )
    assert(expectZoneResponse(webSocketOutTestProbe) === createZoneResponse)
    zone
  }

  private[this] def joinZone(fixture: FixtureParam, zone: Zone): Unit = {
    val (sinkTestProbe,
         zoneValidatorShardRegionTestProbe,
         webSocketOutTestProbe,
         clientConnection) = fixture
    val correlationId = 0L
    sendZoneCommand(sinkTestProbe, clientConnection)(zone.id,
                                                     JoinZoneCommand,
                                                     correlationId)
    val joinZoneResponse = JoinZoneResponse(
      Validated.valid(
        (zone,
         Map(
           ActorRefResolver(system.toTyped)
             .toSerializationFormat(clientConnection) -> publicKey))
      )
    )
    zoneValidatorShardRegionTestProbe.send(
      clientConnection,
      ZoneResponseEnvelope(zoneValidatorShardRegionTestProbe.ref,
                           correlationId,
                           joinZoneResponse)
    )
    assert(expectZoneResponse(webSocketOutTestProbe) === joinZoneResponse)
    val notification = ClientJoinedNotification(
      connectionId = ActorRefResolver(system.toTyped)
        .toSerializationFormat(clientConnection),
      publicKey
    )
    zoneValidatorShardRegionTestProbe.send(
      clientConnection,
      ZoneNotificationEnvelope(zoneValidatorShardRegionTestProbe.ref,
                               zone.id,
                               sequenceNumber = 0,
                               notification)
    )
    assert(expectZoneNotification(webSocketOutTestProbe) === notification)
    ()
  }

  private[this] def quitZone(fixture: FixtureParam, zone: Zone): Unit = {
    val (sinkTestProbe,
         zoneValidatorShardRegionTestProbe,
         webSocketOutTestProbe,
         clientConnection) = fixture
    val correlationId = 0L
    sendZoneCommand(sinkTestProbe, clientConnection)(zone.id,
                                                     QuitZoneCommand,
                                                     correlationId)
    val quitZoneResponse = QuitZoneResponse(Validated.valid(()))
    zoneValidatorShardRegionTestProbe.send(
      clientConnection,
      ZoneResponseEnvelope(zoneValidatorShardRegionTestProbe.ref,
                           correlationId,
                           quitZoneResponse)
    )
    assert(expectZoneResponse(webSocketOutTestProbe) === quitZoneResponse)
    ()
  }

  private[this] def expectClientCommand(webSocketOutTestProbe: TestProbe)
    : proto.ws.protocol.ClientMessage.Command.Command =
    inside(
      webSocketOutTestProbe
        .expectMsgType[proto.ws.protocol.ClientMessage]
        .message) {
      case proto.ws.protocol.ClientMessage.Message.Command(
          proto.ws.protocol.ClientMessage.Command(_, protoCommand)) =>
        protoCommand
    }

  private[this] def sendCreateZoneCommand(sinkTestProbe: TestProbe,
                                          clientConnection: ActorRef)(
      createZoneCommand: CreateZoneCommand,
      correlationId: Long): Unit =
    sendMessage(sinkTestProbe, clientConnection)(
      proto.ws.protocol.ServerMessage.Message.Command(
        proto.ws.protocol.ServerMessage.Command(
          correlationId,
          proto.ws.protocol.ServerMessage.Command.Command.CreateZoneCommand(
            ProtoBinding[CreateZoneCommand,
                         proto.ws.protocol.ZoneCommand.CreateZoneCommand,
                         Any]
              .asProto(createZoneCommand)(()))
        )))

  private[this] def sendZoneCommand(sinkTestProbe: TestProbe,
                                    clientConnection: ActorRef)(
      zoneId: ZoneId,
      zoneCommand: ZoneCommand,
      correlationId: Long): Unit =
    sendMessage(sinkTestProbe, clientConnection)(
      proto.ws.protocol.ServerMessage.Message.Command(
        proto.ws.protocol.ServerMessage.Command(
          correlationId,
          proto.ws.protocol.ServerMessage.Command.Command.ZoneCommandEnvelope(
            proto.ws.protocol.ServerMessage.Command.ZoneCommandEnvelope(
              zoneId = zoneId.value,
              Some(ProtoBinding[ZoneCommand, proto.ws.protocol.ZoneCommand, Any]
                .asProto(zoneCommand)(())))
          )
        )))

  private[this] def sendMessage(sinkTestProbe: TestProbe,
                                clientConnection: ActorRef)(
      message: proto.ws.protocol.ServerMessage.Message): Unit = {
    sinkTestProbe.send(
      clientConnection,
      ActorFlowServerMessage(sinkTestProbe.ref,
                             proto.ws.protocol.ServerMessage(message))
    )
    sinkTestProbe.expectMsg(ActorSinkAck)
    ()
  }

  private[this] def expectZoneResponse(
      webSocketOutTestProbe: TestProbe): ZoneResponse =
    inside(expectMessage(webSocketOutTestProbe)) {
      case proto.ws.protocol.ClientMessage.Message.Response(protoResponse) =>
        inside(protoResponse.response) {
          case proto.ws.protocol.ClientMessage.Response.Response
                .ZoneResponse(protoZoneResponse) =>
            ProtoBinding[ZoneResponse, proto.ws.protocol.ZoneResponse, Any]
              .asScala(protoZoneResponse)(())
        }
    }

  private[this] def expectZoneNotification(
      webSocketOutTestProbe: TestProbe): ZoneNotification =
    inside(expectMessage(webSocketOutTestProbe)) {
      case proto.ws.protocol.ClientMessage.Message
            .Notification(protoNotification) =>
        inside(protoNotification.notification) {
          case proto.ws.protocol.ClientMessage.Notification.Notification
                .ZoneNotificationEnvelope(
                proto.ws.protocol.ClientMessage.Notification
                  .ZoneNotificationEnvelope(_, Some(protoZoneNotification))) =>
            ProtoBinding[ZoneNotification,
                         proto.ws.protocol.ZoneNotification,
                         Any].asScala(protoZoneNotification)(())
        }
    }

  private[this] def expectMessage(webSocketOutTestProbe: TestProbe)
    : proto.ws.protocol.ClientMessage.Message =
    webSocketOutTestProbe.expectMsgType[proto.ws.protocol.ClientMessage].message

}
