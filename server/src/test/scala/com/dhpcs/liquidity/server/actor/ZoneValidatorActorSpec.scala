package com.dhpcs.liquidity.server.actor

import java.net.InetAddress
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

import akka.testkit.TestProbe
import akka.typed
import akka.typed.scaladsl.adapter._
import cats.data.Validated
import com.dhpcs.liquidity.actor.protocol.clientconnection._
import com.dhpcs.liquidity.actor.protocol.zonevalidator._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.server.InmemoryPersistenceTestFixtures
import com.dhpcs.liquidity.testkit.TestKit
import com.dhpcs.liquidity.ws.protocol._
import org.scalactic.TripleEqualsSupport.Spread
import org.scalatest.{Inside, Outcome, fixture}

class ZoneValidatorActorSpec extends fixture.FreeSpec with InmemoryPersistenceTestFixtures with Inside {

  private[this] val remoteAddress = InetAddress.getLoopbackAddress
  private[this] val publicKey     = PublicKey(TestKit.rsaPublicKey.getEncoded)

  override protected type FixtureParam = (TestProbe, ZoneId, typed.ActorRef[SerializableZoneValidatorMessage])

  override protected def withFixture(test: OneArgTest): Outcome = {
    val clientConnectionTestProbe = TestProbe()
    val zoneId                    = ZoneId(UUID.randomUUID.toString)
    val zoneValidator             = system.spawn(ZoneValidatorActor.shardingBehaviour, name = zoneId.persistenceId)
    try withFixture(
      test.toNoArgTest((clientConnectionTestProbe, zoneId, zoneValidator))
    )
    finally system.stop(zoneValidator.toUntyped)
  }

  "A ZoneValidatorActor" - {
    "will reply with a CreateZoneResponse when sending a CreateZoneCommand" in { fixture =>
      val (clientConnectionTestProbe, zoneId, zoneValidator) = fixture
      val correlationId                                      = 0L
      clientConnectionTestProbe.send(
        zoneValidator.toUntyped,
        ZoneCommandEnvelope(
          clientConnectionTestProbe.ref,
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
          assert(zone.created === Spread(pivot = Instant.now().toEpochMilli, tolerance = 5000L))
          assert(
            zone.expires === Spread(pivot = Instant.now().plus(7, ChronoUnit.DAYS).toEpochMilli, tolerance = 5000L))
          assert(zone.transactions === Map.empty)
          assert(zone.name === Some("Dave's Game"))
          assert(zone.metadata === None)
      }
    }
    "will reply with an Error when sending a JoinZoneCommand and no zone has been created" in { fixture =>
      val (clientConnectionTestProbe, zoneId, zoneValidator) = fixture
      val correlationId                                      = 0L
      clientConnectionTestProbe.send(
        zoneValidator.toUntyped,
        ZoneCommandEnvelope(
          clientConnectionTestProbe.ref,
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

  private[this] def expectResponse(clientConnectionTestProbe: TestProbe, correlationId: Long): ZoneResponse = {
    val responseWithIds = clientConnectionTestProbe.expectMsgType[ZoneResponseEnvelope]
    assert(responseWithIds.correlationId === correlationId)
    responseWithIds.zoneResponse
  }
}
