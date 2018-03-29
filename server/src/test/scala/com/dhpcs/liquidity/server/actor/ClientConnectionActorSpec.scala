package com.dhpcs.liquidity.server.actor

import java.net.{InetAddress, InetSocketAddress}
import java.nio.channels.ServerSocketChannel
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.UUID

import akka.actor.typed.ActorRefResolver
import akka.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import com.dhpcs.liquidity.actor.protocol.clientconnection._
import com.dhpcs.liquidity.actor.protocol.zonevalidator._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.server.actor.ClientConnectionActor._
import com.dhpcs.liquidity.server.actor.ClientConnectionActorSpec._
import com.dhpcs.liquidity.ws.protocol._
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{BeforeAndAfterAll, FreeSpec}

class ClientConnectionActorSpec
    extends FreeSpec
    with ActorTestKit
    with BeforeAndAfterAll {

  // TODO: Test via zoneNotificationSource?
  "ClientConnectionActor" in {
    val zoneValidatorShardRegionTestProbe =
      TestProbe[ZoneValidatorMessage]()
    val zoneId = ZoneId(UUID.randomUUID().toString)
    val zoneNotificationOutTestProbe = TestProbe[ActorSourceMessage]()
    val clientConnection = spawn(
      ClientConnectionActor.zoneNotificationBehavior(
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
    clientConnection ! ZoneNotificationEnvelope(
      zoneValidatorShardRegionTestProbe.ref,
      zoneId,
      sequenceNumber = 0,
      ZoneStateNotification(Some(zone), connectedClients)
    )
    zoneNotificationOutTestProbe.expectMessage(
      ForwardZoneNotification(
        ZoneStateNotification(Some(zone), connectedClients)
      )
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
       |  remote.artery {
       |    enabled = on
       |    transport = tcp
       |    canonical.hostname = "localhost"
       |    canonical.port = $akkaRemotingPort
       |  }
       |  cluster {
       |    seed-nodes = ["akka://$name@localhost:$akkaRemotingPort"]
       |    jmx.enabled = off
       |  }
       |}
     """.stripMargin)

  override protected def afterAll(): Unit = shutdownTestKit()

}

object ClientConnectionActorSpec {

  private val publicKey = {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(2048)
    val keyPair = keyPairGenerator.generateKeyPair
    PublicKey(keyPair.getPublic.getEncoded)
  }
}
