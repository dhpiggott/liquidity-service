package com.dhpcs.liquidity.server.actors

import java.security.KeyPairGenerator

import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.testkit.TestProbe
import com.dhpcs.jsonrpc.JsonRpcResponseError
import com.dhpcs.jsonrpc.ResponseCompanion.ErrorResponse
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.protocol._
import com.dhpcs.liquidity.server.actors.ClientConnectionActor.MessageReceivedConfirmation
import com.dhpcs.liquidity.server.actors.ZoneValidatorActor.{
  AuthenticatedCommandWithIds,
  CommandReceivedConfirmation,
  EnvelopedAuthenticatedCommandWithIds,
  ResponseWithIds
}
import org.scalatest.EitherValues._
import org.scalatest.{Inside, Matchers, WordSpec}

class ZoneValidatorActorSpec extends WordSpec with LevelDbPersistenceTestFixtures with Matchers with Inside {

  private[this] val zoneValidatorShardRegion = ClusterSharding(system).start(
    typeName = ZoneValidatorActor.ShardName,
    entityProps = ZoneValidatorActor.props,
    settings = ClusterShardingSettings(system),
    extractEntityId = ZoneValidatorActor.extractEntityId,
    extractShardId = ZoneValidatorActor.extractShardId
  )

  private[this] val publicKey = {
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    PublicKey(publicKeyBytes)
  }

  "A ZoneValidatorActor" should {
    "reply with a CreateZoneResponse when sending a CreateZoneCommand" in {
      val (clientConnectionTestProbe, zoneId) = setup()
      val correlationId                       = Some(Right(BigDecimal(0)))
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
          zone.equityAccountId shouldBe AccountId(0)
          zone.members(MemberId(0)) shouldBe Member(MemberId(0), publicKey, name = Some("Dave"))
          zone.accounts(AccountId(0)) shouldBe Account(AccountId(0), ownerMemberIds = Set(MemberId(0)))
          zone.created should be > 0L
          zone.expires should be > zone.created
          zone.transactions shouldBe Map.empty
          zone.name shouldBe Some("Dave's Game")
          zone.metadata shouldBe None
      }
    }
    "reply with an ErrorResponse when sending a JoinZoneCommand and no zone has been created" in {
      val (clientConnectionTestProbe, zoneId) = setup()
      val correlationId                       = Some(Right(BigDecimal(0)))
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
      expectErrorResponse(clientConnectionTestProbe, correlationId, sequenceNumber) shouldBe
        ErrorResponse(JsonRpcResponseError.ReservedErrorCodeFloor - 1, "Zone does not exist")
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
    commandReceivedConfirmation shouldBe CommandReceivedConfirmation(zoneId, deliveryId)
  }

  private[this] def expectErrorResponse(clientConnectionTestProbe: TestProbe,
                                        correlationId: Option[Either[String, BigDecimal]],
                                        sequenceNumber: Long): ErrorResponse =
    expectResponse(clientConnectionTestProbe, correlationId, sequenceNumber).left.value

  private[this] def expectResultResponse(clientConnectionTestProbe: TestProbe,
                                         correlationId: Option[Either[String, BigDecimal]],
                                         sequenceNumber: Long): ResultResponse =
    expectResponse(clientConnectionTestProbe, correlationId, sequenceNumber).right.value

  private[this] def expectResponse[A](clientConnectionTestProbe: TestProbe,
                                      correlationId: Option[Either[String, BigDecimal]],
                                      sequenceNumber: Long): Either[ErrorResponse, ResultResponse] = {
    val responseWithIds = clientConnectionTestProbe.expectMsgType[ResponseWithIds]
    responseWithIds.correlationId shouldBe correlationId
    responseWithIds.sequenceNumber shouldBe sequenceNumber
    clientConnectionTestProbe.send(
      clientConnectionTestProbe.lastSender,
      MessageReceivedConfirmation(responseWithIds.deliveryId)
    )
    responseWithIds.response
  }
}
