package com.dhpcs.liquidity.server.actor

import java.net.InetAddress
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.testkit.TestProbe
import cats.data.Validated
import com.dhpcs.liquidity.actor.protocol.clientconnection._
import com.dhpcs.liquidity.actor.protocol.zonevalidator._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.server.InmemoryPersistenceTestFixtures
import com.dhpcs.liquidity.testkit.TestKit
import com.dhpcs.liquidity.ws.protocol._
import org.scalactic.TripleEqualsSupport.Spread
import org.scalatest.{FreeSpec, Inside}

class ZoneValidatorActorSpec extends FreeSpec with InmemoryPersistenceTestFixtures with Inside {

  private[this] val zoneValidatorShardRegion = ClusterSharding(system).start(
    typeName = ZoneValidatorActor.ShardTypeName,
    entityProps = ZoneValidatorActor.props,
    settings = ClusterShardingSettings(system),
    extractEntityId = ZoneValidatorActor.extractEntityId,
    extractShardId = ZoneValidatorActor.extractShardId
  )

  private[this] val remoteAddress = InetAddress.getLoopbackAddress
  private[this] val publicKey     = PublicKey(TestKit.rsaPublicKey.getEncoded)

  "A ZoneValidatorActor" - {
    "will reply with a CreateZoneResponse when sending a CreateZoneCommand" in {
      val (clientConnectionTestProbe, zoneId) = setup()
      val correlationId                       = 0L
      clientConnectionTestProbe.send(
        zoneValidatorShardRegion,
        ZoneCommandEnvelope(
          zoneId,
          remoteAddress,
          publicKey,
          correlationId,
          CreateZoneCommand(
            equityOwnerPublicKey = publicKey,
            equityOwnerName = Some("Dave"),
            equityOwnerMetadata = None,
            equityAccountName = None,
            equityAccountMetadata = None,
            name = Some("Dave's Game")
          )
        )
      )
      inside(expectResponse(clientConnectionTestProbe, correlationId)) {
        case CreateZoneResponse(Validated.Valid(zone)) =>
          assert(zone.accounts.size === 1)
          assert(zone.members.size === 1)
          val equityAccount      = zone.accounts(zone.equityAccountId)
          val equityAccountOwner = zone.members(equityAccount.ownerMemberIds.head)
          assert(equityAccount === Account(equityAccount.id, ownerMemberIds = Set(equityAccountOwner.id)))
          assert(equityAccountOwner === Member(equityAccountOwner.id, Set(publicKey), name = Some("Dave")))
          assert(zone.created === Spread(pivot = Instant.now().toEpochMilli, tolerance = 1000L))
          assert(
            zone.expires === Spread(pivot = Instant.now().plus(7, ChronoUnit.DAYS).toEpochMilli, tolerance = 1000L))
          assert(zone.transactions === Map.empty)
          assert(zone.name === Some("Dave's Game"))
          assert(zone.metadata === None)
      }
    }
    "will reply with an Error when sending a JoinZoneCommand and no zone has been created" in {
      val (clientConnectionTestProbe, zoneId) = setup()
      val correlationId                       = 0L
      clientConnectionTestProbe.send(
        zoneValidatorShardRegion,
        ZoneCommandEnvelope(
          zoneId,
          remoteAddress,
          publicKey,
          correlationId,
          JoinZoneCommand
        )
      )
      assert(
        expectResponse(clientConnectionTestProbe, correlationId) === JoinZoneResponse(
          Validated.invalidNel(
            ZoneResponse.Error(
              code = 0,
              description = "Zone must be created."
            )
          ))
      )
    }
  }

  private[this] def setup(): (TestProbe, ZoneId) = {
    val clientConnectionTestProbe = TestProbe()
    val zoneId                    = ZoneId(UUID.randomUUID.toString)
    (clientConnectionTestProbe, zoneId)
  }

  private[this] def expectResponse(clientConnectionTestProbe: TestProbe, correlationId: Long): ZoneResponse = {
    val responseWithIds = clientConnectionTestProbe.expectMsgType[ZoneResponseEnvelope]
    assert(responseWithIds.correlationId === correlationId)
    responseWithIds.zoneResponse
  }
}
