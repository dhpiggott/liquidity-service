package com.dhpcs.liquidity.server.actors

import java.security.KeyPairGenerator

import akka.testkit.TestProbe
import com.dhpcs.jsonrpc.JsonRpcResponseError
import com.dhpcs.jsonrpc.ResponseCompanion.ErrorResponse
import com.dhpcs.liquidity.model.{PublicKey, ZoneId}
import com.dhpcs.liquidity.protocol._
import com.dhpcs.liquidity.server.actors.ClientConnectionActor.MessageReceivedConfirmation
import com.dhpcs.liquidity.server.actors.ZoneValidatorActor.{AuthenticatedCommandWithIds, CommandReceivedConfirmation, EnvelopedMessage, ResponseWithIds}
import org.scalatest.EitherValues._
import org.scalatest.{Inside, MustMatchers, WordSpec}

class ZoneValidatorActorSpec extends WordSpec
  with Inside with MustMatchers with ZoneValidatorShardRegionProvider {
  private[this] val publicKey = {
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    PublicKey(publicKeyBytes)
  }

  "A ZoneValidatorActor" must {
    "send an error response when joined before creation" in {
      val (clientConnectionTestProbe, zoneId, sequenceNumber) = setup()
      send(clientConnectionTestProbe, zoneId)(
        AuthenticatedCommandWithIds(
          publicKey,
          JoinZoneCommand(
            zoneId
          ),
          correlationId = None,
          sequenceNumber,
          deliveryId = 0L
        )
      )
      expectError(clientConnectionTestProbe)(error =>
        error mustBe ErrorResponse(JsonRpcResponseError.ReservedErrorCodeFloor - 1, "Zone does not exist")
      )
    }
    "send a create zone response when a zone is created" in {
      val (clientConnectionTestProbe, zoneId, sequenceNumber) = setup()
      send(clientConnectionTestProbe, zoneId)(
        EnvelopedMessage(
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
            correlationId = None,
            sequenceNumber,
            deliveryId = 0L
          )
        )
      )
      expectResult(clientConnectionTestProbe)(result =>
        result mustBe a[CreateZoneResponse]
      )
    }
    "send a JoinZoneResponse when joined" in {
      Thread.sleep(10000)
      val (clientConnectionTestProbe, zoneId, sequenceNumber) = setup()
      send(clientConnectionTestProbe, zoneId)(
        EnvelopedMessage(
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
            correlationId = None,
            sequenceNumber,
            deliveryId = 0L
          )
        )
      )
      expectResult(clientConnectionTestProbe)(result =>
        result mustBe a[CreateZoneResponse]
      )
      send(clientConnectionTestProbe, zoneId)(
        AuthenticatedCommandWithIds(
          publicKey,
          JoinZoneCommand(
            zoneId
          ),
          correlationId = None,
          sequenceNumber + 1,
          deliveryId = 0L
        )
      )
      expectResult(clientConnectionTestProbe)(result => inside(result) {
        case JoinZoneResponse(_, connectedClients) => connectedClients mustBe Set(publicKey)
      })
    }
  }

  private[this] def setup(): (TestProbe, ZoneId, Long) = {
    val clientConnectionTestProbe = TestProbe()
    val zoneId = ZoneId.generate
    val sequenceNumber = 0L
    (clientConnectionTestProbe, zoneId, sequenceNumber)
  }

  private[this] def send(clientConnectionTestProbe: TestProbe, zoneId: ZoneId)
                        (message: Any): Unit = {
    clientConnectionTestProbe.send(
      zoneValidatorShardRegion,
      message
    )
    clientConnectionTestProbe.expectMsgPF() {
      case CommandReceivedConfirmation(`zoneId`, _) =>
    }
  }

  private[this] def expectError(clientConnectionTestProbe: TestProbe)
                               (f: ErrorResponse => Unit): Unit =
    expect(clientConnectionTestProbe)(response =>
      f(response.left.value)
    )

  private[this] def expectResult(clientConnectionTestProbe: TestProbe)
                                (f: ResultResponse => Unit): Unit =
    expect(clientConnectionTestProbe)(response =>
      f(response.right.value)
    )

  private[this] def expect(clientConnectionTestProbe: TestProbe)
                          (f: Either[ErrorResponse, ResultResponse] => Unit): Unit = {
    val zoneDeliveryId = clientConnectionTestProbe.expectMsgPF() {
      case ResponseWithIds(response, None, _, id) => f(response); id
    }
    clientConnectionTestProbe.send(
      clientConnectionTestProbe.lastSender,
      MessageReceivedConfirmation(zoneDeliveryId)
    )
  }
}
