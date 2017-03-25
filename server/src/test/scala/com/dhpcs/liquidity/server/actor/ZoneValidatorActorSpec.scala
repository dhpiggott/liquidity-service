package com.dhpcs.liquidity.server.actor

import java.security.KeyPairGenerator
import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.testkit.TestProbe
import com.dhpcs.jsonrpc.JsonRpcMessage.{CorrelationId, NumericCorrelationId}
import com.dhpcs.jsonrpc.JsonRpcResponseErrorMessage
import com.dhpcs.liquidity.actor.protocol._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.server.InMemPersistenceTestFixtures
import com.dhpcs.liquidity.ws.protocol._
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
      val correlationId                       = NumericCorrelationId(0)
      val sequenceNumber                      = 1L
      send(clientConnectionTestProbe)(
        EnvelopedAuthenticatedCommandWithIds(
          zoneId,
          AuthenticatedCommandWithIds(
            publicKey,
            CreateZoneCommand(
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
      )
      inside(expectResultResponse(clientConnectionTestProbe, correlationId, sequenceNumber)) {
        case CreateZoneResponse(zone) =>
          zone.equityAccountId === AccountId(0)
          zone.members(MemberId(0)) === Member(MemberId(0), publicKey, name = Some("Dave"))
          zone.accounts(AccountId(0)) === Account(AccountId(0), ownerMemberIds = Set(MemberId(0)))
          zone.created === Spread(pivot = Instant.now().toEpochMilli, tolerance = 100L)
          zone.expires === Spread(pivot = Instant.now().plus(2, ChronoUnit.DAYS).toEpochMilli, tolerance = 100L)
          zone.transactions === Map.empty
          zone.name === Some("Dave's Game")
          zone.metadata === None
      }
    }
    "will reply with an ErrorResponse when sending a JoinZoneCommand and no zone has been created" in {
      val (clientConnectionTestProbe, zoneId) = setup()
      val correlationId                       = NumericCorrelationId(0)
      val sequenceNumber                      = 1L
      send(clientConnectionTestProbe, zoneId)(
        AuthenticatedCommandWithIds(
          publicKey,
          JoinZoneCommand(
            zoneId
          ),
          correlationId,
          sequenceNumber,
          deliveryId = 1L
        )
      )
      expectErrorResponse(clientConnectionTestProbe, correlationId, sequenceNumber) ===
        JsonRpcResponseErrorMessage.applicationError(
          code = JsonRpcResponseErrorMessage.ReservedErrorCodeFloor - 1,
          message = "Zone does not exist",
          data = None,
          correlationId
        )
    }
  }

  private[this] def setup(): (TestProbe, ZoneId) = {
    val clientConnectionTestProbe = TestProbe()
    val zoneId                    = ZoneId.generate
    (clientConnectionTestProbe, zoneId)
  }

  private[this] def send(clientConnectionTestProbe: TestProbe)(
      envelopedAuthenticatedCommandWithIds: EnvelopedAuthenticatedCommandWithIds): Unit = {
    val zoneId     = envelopedAuthenticatedCommandWithIds.zoneId
    val deliveryId = envelopedAuthenticatedCommandWithIds.authenticatedCommandWithIds.deliveryId
    send(clientConnectionTestProbe, message = envelopedAuthenticatedCommandWithIds, zoneId, deliveryId)
  }

  private[this] def send(clientConnectionTestProbe: TestProbe, zoneId: ZoneId)(
      authenticatedCommandWithIds: AuthenticatedCommandWithIds): Unit = {
    val deliveryId = authenticatedCommandWithIds.deliveryId
    send(clientConnectionTestProbe, message = authenticatedCommandWithIds, zoneId, deliveryId)
  }

  private[this] def send(clientConnectionTestProbe: TestProbe, message: Any, zoneId: ZoneId, deliveryId: Long): Unit = {
    clientConnectionTestProbe.send(
      zoneValidatorShardRegion,
      message
    )
    val commandReceivedConfirmation = clientConnectionTestProbe.expectMsgType[CommandReceivedConfirmation]
    commandReceivedConfirmation === CommandReceivedConfirmation(zoneId, deliveryId)
  }

  private[this] def expectErrorResponse(clientConnectionTestProbe: TestProbe,
                                        correlationId: CorrelationId,
                                        sequenceNumber: Long): JsonRpcResponseErrorMessage = {
    val responseWithIds = clientConnectionTestProbe.expectMsgType[ErrorResponseWithIds]
    responseWithIds.response.id === correlationId
    responseWithIds.sequenceNumber === sequenceNumber
    clientConnectionTestProbe.send(
      clientConnectionTestProbe.lastSender,
      MessageReceivedConfirmation(responseWithIds.deliveryId)
    )
    responseWithIds.response
  }

  private[this] def expectResultResponse(clientConnectionTestProbe: TestProbe,
                                         correlationId: CorrelationId,
                                         sequenceNumber: Long): Response = {
    val responseWithIds = clientConnectionTestProbe.expectMsgType[SuccessResponseWithIds]
    responseWithIds.correlationId === correlationId
    responseWithIds.sequenceNumber === sequenceNumber
    clientConnectionTestProbe.send(
      clientConnectionTestProbe.lastSender,
      MessageReceivedConfirmation(responseWithIds.deliveryId)
    )
    responseWithIds.response
  }
}
