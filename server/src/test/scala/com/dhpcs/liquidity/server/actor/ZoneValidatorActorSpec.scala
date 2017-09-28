package com.dhpcs.liquidity.server.actor

import java.net.InetAddress
import java.time.Instant
import java.time.temporal.ChronoUnit

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
      val sequenceNumber                      = 1L
      send(clientConnectionTestProbe, zoneId)(
        ZoneCommandEnvelope(
          zoneId,
          remoteAddress,
          publicKey,
          correlationId,
          sequenceNumber,
          deliveryId = 1L,
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
      inside(expectResponse(clientConnectionTestProbe, correlationId, sequenceNumber)) {
        case CreateZoneResponse(Validated.Valid(zone)) =>
          assert(zone.equityAccountId === AccountId("0"))
          assert(zone.members(MemberId("0")) === Member(MemberId("0"), Set(publicKey), name = Some("Dave")))
          assert(zone.accounts(AccountId("0")) === Account(AccountId("0"), ownerMemberIds = Set(MemberId("0"))))
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
      val sequenceNumber                      = 1L
      send(clientConnectionTestProbe, zoneId)(
        ZoneCommandEnvelope(
          zoneId,
          remoteAddress,
          publicKey,
          correlationId,
          sequenceNumber,
          deliveryId = 1L,
          JoinZoneCommand
        )
      )
      assert(
        expectResponse(clientConnectionTestProbe, correlationId, sequenceNumber) === JoinZoneResponse(
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
    val zoneId                    = ZoneId.generate
    (clientConnectionTestProbe, zoneId)
  }

  private[this] def send(clientConnectionTestProbe: TestProbe, zoneId: ZoneId)(
      zoneCommandEnvelope: ZoneCommandEnvelope): Unit = {
    val deliveryId = zoneCommandEnvelope.deliveryId
    send(clientConnectionTestProbe, message = zoneCommandEnvelope, zoneId, deliveryId)
  }

  private[this] def send(clientConnectionTestProbe: TestProbe, message: Any, zoneId: ZoneId, deliveryId: Long): Unit = {
    clientConnectionTestProbe.send(
      zoneValidatorShardRegion,
      message
    )
    clientConnectionTestProbe.expectMsg(ZoneCommandReceivedConfirmation(zoneId, deliveryId)); ()
  }

  private[this] def expectResponse(clientConnectionTestProbe: TestProbe,
                                   correlationId: Long,
                                   sequenceNumber: Long): ZoneResponse = {
    val responseWithIds = clientConnectionTestProbe.expectMsgType[ZoneResponseEnvelope]
    assert(responseWithIds.correlationId === correlationId)
    assert(responseWithIds.sequenceNumber === sequenceNumber)
    clientConnectionTestProbe.send(
      clientConnectionTestProbe.lastSender,
      MessageReceivedConfirmation(responseWithIds.deliveryId)
    )
    responseWithIds.zoneResponse
  }
}
