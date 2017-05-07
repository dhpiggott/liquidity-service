package com.dhpcs.liquidity.server.actor

import java.security.KeyPairGenerator
import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.testkit.TestProbe
import com.dhpcs.liquidity.actor.protocol.ZoneValidatorMessage._
import com.dhpcs.liquidity.actor.protocol._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.server.InMemPersistenceTestFixtures
import org.scalactic.TripleEqualsSupport.Spread
import org.scalatest.{FreeSpec, Inside}

class ZoneValidatorActorSpec extends FreeSpec with InMemPersistenceTestFixtures with Inside {

  private[this] val zoneValidatorShardRegion = ClusterSharding(system).start(
    typeName = ZoneValidatorActor.ShardTypeName,
    entityProps = ZoneValidatorActor.props,
    settings = ClusterShardingSettings(system),
    extractEntityId = ZoneValidatorActor.extractEntityId,
    extractShardId = ZoneValidatorActor.extractShardId
  )

  private[this] val publicKey = {
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    PublicKey(publicKeyBytes)
  }

  "A ZoneValidatorActor" - {
    "will reply with a CreateZoneResponse when sending a CreateZoneCommand" in {
      val (clientConnectionTestProbe, zoneId) = setup()
      val correlationId                       = 0L
      val sequenceNumber                      = 1L
      send(clientConnectionTestProbe, zoneId)(
        AuthenticatedZoneCommandWithIds(
          publicKey,
          CreateZoneCommand(
            zoneId,
            equityOwnerPublicKey = publicKey,
            equityOwnerName = Some("Dave"),
            equityOwnerMetadata = None,
            equityAccountName = None,
            equityAccountMetadata = None,
            name = Some("Dave's Game")
          ),
          correlationId,
          sequenceNumber,
          deliveryId = 1L
        )
      )
      inside(expectResponse(clientConnectionTestProbe, correlationId, sequenceNumber)) {
        case CreateZoneResponse(zone) =>
          assert(zone.equityAccountId === AccountId(0))
          assert(zone.members(MemberId(0)) === Member(MemberId(0), publicKey, name = Some("Dave")))
          assert(zone.accounts(AccountId(0)) === Account(AccountId(0), ownerMemberIds = Set(MemberId(0))))
          assert(zone.created === Spread(pivot = Instant.now().toEpochMilli, tolerance = 500L))
          assert(
            zone.expires === Spread(pivot = Instant.now().plus(2, ChronoUnit.DAYS).toEpochMilli, tolerance = 500L))
          assert(zone.transactions === Map.empty)
          assert(zone.name === Some("Dave's Game"))
          assert(zone.metadata === None)
      }
    }
    "will reply with an ErrorResponse when sending a JoinZoneCommand and no zone has been created" in {
      val (clientConnectionTestProbe, zoneId) = setup()
      val correlationId                       = 0L
      val sequenceNumber                      = 1L
      send(clientConnectionTestProbe, zoneId)(
        AuthenticatedZoneCommandWithIds(
          publicKey,
          JoinZoneCommand(
            zoneId
          ),
          correlationId,
          sequenceNumber,
          deliveryId = 1L
        )
      )
      assert(
        expectResponse(clientConnectionTestProbe, correlationId, sequenceNumber) === ErrorResponse(
          "Zone does not exist")
      )
    }
  }

  private[this] def setup(): (TestProbe, ZoneId) = {
    val clientConnectionTestProbe = TestProbe()
    val zoneId                    = ZoneId.generate
    (clientConnectionTestProbe, zoneId)
  }

  private[this] def send(clientConnectionTestProbe: TestProbe, zoneId: ZoneId)(
      authenticatedCommandWithIds: AuthenticatedZoneCommandWithIds): Unit = {
    val deliveryId = authenticatedCommandWithIds.deliveryId
    send(clientConnectionTestProbe, message = authenticatedCommandWithIds, zoneId, deliveryId)
  }

  private[this] def send(clientConnectionTestProbe: TestProbe, message: Any, zoneId: ZoneId, deliveryId: Long): Unit = {
    clientConnectionTestProbe.send(
      zoneValidatorShardRegion,
      message
    )
    clientConnectionTestProbe.expectMsg(ZoneCommandReceivedConfirmation(zoneId, deliveryId))
  }

  private[this] def expectResponse(clientConnectionTestProbe: TestProbe,
                                   correlationId: Long,
                                   sequenceNumber: Long): ZoneResponse = {
    val responseWithIds = clientConnectionTestProbe.expectMsgType[ZoneResponseWithIds]
    assert(responseWithIds.correlationId === correlationId)
    assert(responseWithIds.sequenceNumber === sequenceNumber)
    clientConnectionTestProbe.send(
      clientConnectionTestProbe.lastSender,
      MessageReceivedConfirmation(responseWithIds.deliveryId)
    )
    responseWithIds.response
  }
}
