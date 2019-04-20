package com.dhpcs.liquidity.service.actor

import java.net.{InetAddress, InetSocketAddress}
import java.nio.channels.ServerSocketChannel
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.UUID

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorRefResolver
import akka.actor.typed.scaladsl.adapter._
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{ActorMaterializer, Materializer}
import com.dhpcs.liquidity.actor.protocol.clientconnection._
import com.dhpcs.liquidity.actor.protocol.zonevalidator._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.service.actor.ClientConnectionActorSpec._
import com.dhpcs.liquidity.ws.protocol._
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, FreeSpec}

import scala.concurrent.Future

class ClientConnectionActorSpec extends FreeSpec with BeforeAndAfterAll {

  private[this] val testKit = ActorTestKit(
    "clientConnectionActorSpec",
    ConfigFactory.parseString(s"""
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
         |    seed-nodes = ["akka://clientConnectionActorSpec@localhost:$akkaRemotingPort"]
         |    jmx.enabled = off
         |  }
         |}
       """.stripMargin)
  )

  "ClientConnectionActor" in {
    val zoneValidatorShardRegionTestProbe =
      testKit.createTestProbe[ZoneValidatorMessage]("zoneValidatorShardRegion")
    val zoneId = ZoneId(UUID.randomUUID().toString)
    implicit val mat: Materializer =
      ActorMaterializer()(testKit.system.toUntyped)
    val zoneNotificationOutTestSink =
      ClientConnectionActor
        .zoneNotificationSource(
          zoneValidatorShardRegionTestProbe.ref,
          remoteAddress,
          publicKey,
          zoneId,
          behavior => Future.successful(testKit.spawn(behavior))
        )
        .runWith(TestSink.probe[ZoneNotification](testKit.system.toUntyped))
    val created = Instant.now()
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
      expires = created.plus(java.time.Duration.ofDays(30)),
      name = Some("Dave's Game"),
      metadata = None
    )
    val zoneNotificationSubscription = zoneValidatorShardRegionTestProbe
      .expectMessageType[ZoneNotificationSubscription]
    val connectedClients = Map(
      ActorRefResolver(testKit.system)
        .toSerializationFormat(zoneNotificationSubscription.subscriber) ->
        publicKey
    )
    zoneNotificationSubscription.subscriber ! ZoneNotificationEnvelope(
      zoneValidatorShardRegionTestProbe.ref,
      zoneId,
      sequenceNumber = 0,
      ZoneStateNotification(Some(zone), connectedClients)
    )
    zoneNotificationOutTestSink.requestNext(
      ZoneStateNotification(Some(zone), connectedClients)
    )
  }

  private[this] lazy val akkaRemotingPort = {
    val serverSocket = ServerSocketChannel.open().socket()
    serverSocket.bind(new InetSocketAddress("localhost", 0))
    val port = serverSocket.getLocalPort
    serverSocket.close()
    port
  }

  override protected def afterAll(): Unit = testKit.shutdownTestKit()

}

object ClientConnectionActorSpec {

  private val remoteAddress = InetAddress.getLoopbackAddress
  private val publicKey = {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(2048)
    val keyPair = keyPairGenerator.generateKeyPair
    PublicKey(keyPair.getPublic.getEncoded)
  }
}
