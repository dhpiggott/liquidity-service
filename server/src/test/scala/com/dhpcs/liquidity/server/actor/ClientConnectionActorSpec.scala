package com.dhpcs.liquidity.server.actor

import java.net.{InetAddress, InetSocketAddress}
import java.nio.channels.ServerSocketChannel
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.security.{KeyPairGenerator, Signature}
import java.time.Instant
import java.util.UUID

import akka.actor.typed.{ActorRef, ActorRefResolver}
import akka.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import cats.data.Validated
import cats.syntax.validated._
import com.dhpcs.liquidity.actor.protocol.clientconnection._
import com.dhpcs.liquidity.actor.protocol.zonevalidator._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.server.actor.ClientConnectionActor._
import com.dhpcs.liquidity.server.actor.ClientConnectionActorSpec._
import com.dhpcs.liquidity.ws.protocol.ProtoBindings._
import com.dhpcs.liquidity.ws.protocol._
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.Inside._
import org.scalatest.{BeforeAndAfterAll, Outcome, fixture}

import scala.concurrent.duration._
import scala.util.Random

class ClientConnectionActorSpec
    extends fixture.FreeSpec
    with ActorTestKit
    with BeforeAndAfterAll {

  // TODO: Test via zoneNotificationSource?
  "A ZoneNotification ClientConnectionActor" - {
    "receiving a JoinZoneResponse" - {
      "relays it" in { _ =>
        val zoneValidatorShardRegionTestProbe =
          TestProbe[ZoneValidatorMessage]()
        val zoneId = ZoneId(UUID.randomUUID().toString)
        val zoneNotificationOutTestProbe = TestProbe[ActorSourceMessage]()
        val clientConnection = spawn(
          ClientConnectionActor.zoneNotificationBehavior(
            pingInterval = 3.seconds,
            zoneValidatorShardRegionTestProbe.ref,
            InetAddress.getLoopbackAddress,
            publicKey,
            zoneId,
            zoneNotificationOutTestProbe.ref
          )
        )
        val created = Instant.now().toEpochMilli
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
        val connectedClients = Map(
          ActorRefResolver(system)
            .toSerializationFormat(clientConnection) ->
            publicKey
        )
        clientConnection ! ZoneResponseEnvelope(
          zoneValidatorShardRegionTestProbe.ref,
          correlationId = 0,
          JoinZoneResponse((zone, connectedClients).valid)
        )
        zoneNotificationOutTestProbe.expectMessage(
          ForwardZoneNotification(
            ZoneStateNotification(zone, connectedClients)
          )
        )
      }
    }
    "receiving a MemberCreatedNotification" - {
      "relays it" in { _ =>
        val zoneValidatorShardRegionTestProbe =
          TestProbe[ZoneValidatorMessage]()
        val zoneId = ZoneId(UUID.randomUUID().toString)
        val zoneNotificationOutTestProbe = TestProbe[ActorSourceMessage]()
        val clientConnection = spawn(
          ClientConnectionActor.zoneNotificationBehavior(
            pingInterval = 3.seconds,
            zoneValidatorShardRegionTestProbe.ref,
            InetAddress.getLoopbackAddress,
            publicKey,
            zoneId,
            zoneNotificationOutTestProbe.ref
          )
        )
        val member = Member(
          id = MemberId("1"),
          ownerPublicKeys = Set(publicKey),
          name = Some("Jenny"),
          metadata = None
        )
        clientConnection ! ZoneNotificationEnvelope(
          zoneValidatorShardRegionTestProbe.ref,
          zoneId,
          sequenceNumber = 0,
          MemberCreatedNotification(member)
        )
        zoneNotificationOutTestProbe.expectMessage(
          ForwardZoneNotification(
            MemberCreatedNotification(member)
          )
        )
      }
    }
    "left idle" - {
      "sends a PingNotification" in { _ =>
        val zoneValidatorShardRegionTestProbe =
          TestProbe[ZoneValidatorMessage]()
        val zoneId = ZoneId(UUID.randomUUID().toString)
        val zoneNotificationOutTestProbe = TestProbe[ActorSourceMessage]()
        spawn(
          ClientConnectionActor.zoneNotificationBehavior(
            pingInterval = 3.seconds,
            zoneValidatorShardRegionTestProbe.ref,
            InetAddress.getLoopbackAddress,
            publicKey,
            zoneId,
            zoneNotificationOutTestProbe.ref
          )
        )
        zoneNotificationOutTestProbe.within(3.5.seconds)(
          zoneNotificationOutTestProbe.expectMessage(
            ForwardZoneNotification(
              PingNotification(())
            )
          )
        )
      }
    }
  }

  // TODO: Test via webSocketFlow?
  "A WebSocket ClientConnectionActor" - {
    "receiving a KeyOwnershipProof" - {
      "rejects it if the signature is invalid" in { fixture =>
        val (_, _, webSocketOutTestProbe, _) = fixture
        inside(expectMessage(fixture)) {
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
        sendMessage(fixture)(
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
        inside(expectMessage(fixture)) {
          case proto.ws.protocol.ClientMessage.Message
                .KeyOwnershipChallenge(_) =>
            ()
        }
        receivePing(fixture)
      }
    }
  }

  override protected type FixtureParam =
    ClientConnectionActorSpec.FixtureParam

  // TODO: ZoneNotificationSource version
  override protected def withFixture(test: OneArgTest): Outcome = {
    val sinkTestProbe = TestProbe[ActorSinkAck.type]()
    val zoneValidatorShardRegionTestProbe = TestProbe[ZoneValidatorMessage]()
    val webSocketOutTestProbe = TestProbe[proto.ws.protocol.ClientMessage]()
    val clientConnection = spawn(
      ClientConnectionActor.webSocketBehavior(
        pingInterval = 3.seconds,
        zoneValidatorShardRegionTestProbe.ref,
        InetAddress.getLoopbackAddress,
        webSocketOutTestProbe.ref
      )
    )
    clientConnection ! InitActorSink(sinkTestProbe.ref)
    sinkTestProbe.expectMessage(ActorSinkAck)
    withFixture(
      test.toNoArgTest(
        (sinkTestProbe,
         zoneValidatorShardRegionTestProbe,
         webSocketOutTestProbe,
         clientConnection))
    )
  }

  private[this] lazy val akkaRemotingPort = {
    val serverSocket = ServerSocketChannel.open().socket()
    serverSocket.bind(new InetSocketAddress("localhost", 0))
    val port = serverSocket.getLocalPort
    serverSocket.close()
    port
  }

  override def config: Config = ConfigFactory.parseString(s"""
       |akka {
       |  loglevel = "WARNING"
       |  actor.provider = "cluster"
       |  remote.netty.tcp {
       |    hostname = "localhost"
       |    port = $akkaRemotingPort
       |  }
       |  cluster {
       |    metrics.enabled = off
       |    seed-nodes = ["akka.tcp://$name@localhost:$akkaRemotingPort"]
       |    jmx.multi-mbeans-in-same-jvm = on
       |  }
       |}
     """.stripMargin)

  private[this] def receivePing(fixture: FixtureParam): Unit = {
    val (_, _, webSocketOutTestProbe, _) = fixture
    webSocketOutTestProbe.within(3.5.seconds)(
      assert(
        expectClientCommand(fixture) === proto.ws.protocol.ClientMessage.Command.Command
          .PingCommand(com.google.protobuf.ByteString.EMPTY)
      )
    )
    ()
  }

  private[this] def authenticate(fixture: FixtureParam): Unit = {
    val keyOwnershipChallenge = inside(expectMessage(fixture)) {
      case proto.ws.protocol.ClientMessage.Message
            .KeyOwnershipChallenge(value) =>
        value
    }
    sendMessage(fixture)(
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
    val (_, zoneValidatorShardRegionTestProbe, _, clientConnection) = fixture
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
    sendCreateZoneCommand(fixture)(createZoneCommand, correlationId)
    val zoneCommandEnvelope =
      zoneValidatorShardRegionTestProbe.expectMessageType[ZoneCommandEnvelope]
    assert(zoneCommandEnvelope.publicKey === publicKey)
    assert(zoneCommandEnvelope.correlationId === correlationId)
    val zoneId = zoneCommandEnvelope.zoneId
    val created = Instant.now().toEpochMilli
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
    clientConnection ! ZoneResponseEnvelope(
      zoneValidatorShardRegionTestProbe.ref,
      correlationId,
      createZoneResponse
    )
    assert(expectZoneResponse(fixture) === createZoneResponse)
    zone
  }

  private[this] def joinZone(fixture: FixtureParam, zone: Zone): Unit = {
    val (_, zoneValidatorShardRegionTestProbe, _, clientConnection) = fixture
    val correlationId = 0L
    sendZoneCommand(fixture)(zone.id, JoinZoneCommand, correlationId)
    val joinZoneResponse = JoinZoneResponse(
      Validated.valid(
        (zone,
         Map(
           ActorRefResolver(system)
             .toSerializationFormat(clientConnection) -> publicKey))
      )
    )
    clientConnection ! ZoneResponseEnvelope(
      zoneValidatorShardRegionTestProbe.ref,
      correlationId,
      joinZoneResponse
    )
    assert(expectZoneResponse(fixture) === joinZoneResponse)
    val notification = ClientJoinedNotification(
      connectionId = ActorRefResolver(system)
        .toSerializationFormat(clientConnection),
      publicKey
    )
    clientConnection ! ZoneNotificationEnvelope(
      zoneValidatorShardRegionTestProbe.ref,
      zone.id,
      sequenceNumber = 0,
      notification
    )
    assert(expectZoneNotification(fixture) === notification)
    ()
  }

  private[this] def quitZone(fixture: FixtureParam, zone: Zone): Unit = {
    val (_, zoneValidatorShardRegionTestProbe, _, clientConnection) = fixture
    val correlationId = 0L
    sendZoneCommand(fixture)(zone.id, QuitZoneCommand, correlationId)
    val quitZoneResponse = QuitZoneResponse(Validated.valid(()))
    clientConnection ! ZoneResponseEnvelope(
      zoneValidatorShardRegionTestProbe.ref,
      correlationId,
      quitZoneResponse
    )
    assert(expectZoneResponse(fixture) === quitZoneResponse)
    ()
  }

  override protected def afterAll(): Unit = shutdownTestKit()

}

object ClientConnectionActorSpec {

  private type FixtureParam = (TestProbe[ActorSinkAck.type],
                               TestProbe[ZoneValidatorMessage],
                               TestProbe[proto.ws.protocol.ClientMessage],
                               ActorRef[ClientConnectionMessage])

  private val (rsaPrivateKey: RSAPrivateKey, rsaPublicKey: RSAPublicKey) = {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(2048)
    val keyPair = keyPairGenerator.generateKeyPair
    (keyPair.getPrivate, keyPair.getPublic)
  }

  private val publicKey = PublicKey(rsaPublicKey.getEncoded)

  private def createKeyOwnershipProof(
      publicKey: RSAPublicKey,
      privateKey: RSAPrivateKey,
      keyOwnershipChallenge: proto.ws.protocol.ClientMessage.KeyOwnershipChallenge)
    : proto.ws.protocol.ServerMessage.KeyOwnershipProof = {
    def signMessage(privateKey: RSAPrivateKey)(
        message: Array[Byte]): Array[Byte] = {
      val s = Signature.getInstance("SHA256withRSA")
      s.initSign(privateKey)
      s.update(message)
      s.sign
    }
    val nonce = keyOwnershipChallenge.nonce.toByteArray
    proto.ws.protocol.ServerMessage.KeyOwnershipProof(
      com.google.protobuf.ByteString.copyFrom(publicKey.getEncoded),
      com.google.protobuf.ByteString.copyFrom(
        signMessage(privateKey)(nonce)
      )
    )
  }

  private def sendCreateZoneCommand(fixture: FixtureParam)(
      createZoneCommand: CreateZoneCommand,
      correlationId: Long): Unit = {
    sendMessage(fixture)(
      proto.ws.protocol.ServerMessage.Message.Command(
        proto.ws.protocol.ServerMessage.Command(
          correlationId,
          proto.ws.protocol.ServerMessage.Command.Command.CreateZoneCommand(
            ProtoBinding[CreateZoneCommand,
                         proto.ws.protocol.ZoneCommand.CreateZoneCommand,
                         Any]
              .asProto(createZoneCommand)(()))
        )))
  }

  private def sendZoneCommand(fixture: FixtureParam)(
      zoneId: ZoneId,
      zoneCommand: ZoneCommand,
      correlationId: Long): Unit =
    sendMessage(fixture)(
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

  private def sendMessage(fixture: FixtureParam)(
      message: proto.ws.protocol.ServerMessage.Message): Unit = {
    val (sinkTestProbe, _, _, clientConnection) = fixture
    clientConnection ! ActorFlowServerMessage(
      sinkTestProbe.ref,
      proto.ws.protocol.ServerMessage(message))
    sinkTestProbe.expectMessage(ActorSinkAck)
    ()
  }

  private def expectClientCommand(
      fixture: FixtureParam): proto.ws.protocol.ClientMessage.Command.Command =
    inside(expectMessage(fixture)) {
      case proto.ws.protocol.ClientMessage.Message.Command(
          proto.ws.protocol.ClientMessage.Command(_, protoCommand)) =>
        protoCommand
    }

  private def expectZoneResponse(fixture: FixtureParam): ZoneResponse =
    inside(expectMessage(fixture)) {
      case proto.ws.protocol.ClientMessage.Message.Response(protoResponse) =>
        inside(protoResponse.response) {
          case proto.ws.protocol.ClientMessage.Response.Response
                .ZoneResponse(protoZoneResponse) =>
            ProtoBinding[ZoneResponse, proto.ws.protocol.ZoneResponse, Any]
              .asScala(protoZoneResponse)(())
        }
    }

  private def expectZoneNotification(fixture: FixtureParam): ZoneNotification =
    inside(expectMessage(fixture)) {
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

  private def expectMessage(
      fixture: FixtureParam): proto.ws.protocol.ClientMessage.Message = {
    val (_, _, webSocketOutTestProbe, _) = fixture
    webSocketOutTestProbe
      .expectMessageType[proto.ws.protocol.ClientMessage]
      .message
  }
}
