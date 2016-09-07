package actors

import java.security.KeyPairGenerator

import actors.ClientConnection.MessageReceivedConfirmation
import actors.ZoneValidator.{AuthenticatedCommandWithIds, CommandReceivedConfirmation, EnvelopedMessage, ResponseWithIds}
import akka.testkit.TestProbe
import com.dhpcs.jsonrpc.JsonRpcResponseError
import com.dhpcs.jsonrpc.ResponseCompanion.ErrorResponse
import com.dhpcs.liquidity.models._
import org.scalatest.WordSpec

import scala.concurrent.duration._
import scala.util.Left

class ZoneValidatorSpec extends WordSpec with ZoneValidatorShardRegionProvider {
  private[this] val publicKey = {
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    PublicKey(publicKeyBytes)
  }

  private[this] val unusedClientCorrelationId = Option(Left("unused"))
  private[this] val unusedClientDeliveryId = 0L

  private[this] var commandSequenceNumbers = Map.empty[ZoneId, Long].withDefaultValue(0L)

  "A ZoneValidator" must {
    "send an error response when joined before creation" in {
      val clientConnectionTestProbe = TestProbe()
      val zoneId = ZoneId.generate
      val sequenceNumber = commandSequenceNumbers(zoneId)
      commandSequenceNumbers = commandSequenceNumbers + (zoneId -> (sequenceNumber + 1))
      clientConnectionTestProbe.send(
        zoneValidatorShardRegion,
        AuthenticatedCommandWithIds(
          publicKey,
          JoinZoneCommand(
            zoneId
          ),
          unusedClientCorrelationId,
          sequenceNumber,
          unusedClientDeliveryId
        )
      )
      clientConnectionTestProbe.expectMsgPF(10.seconds) {
        case CommandReceivedConfirmation(`zoneId`, _) =>
      }
      val expectedResponse = Left(
        ErrorResponse(
          JsonRpcResponseError.ReservedErrorCodeFloor - 1,
          "Zone does not exist"
        )
      )
      val zoneDeliveryId = clientConnectionTestProbe.expectMsgPF() {
        case ResponseWithIds(`expectedResponse`, `unusedClientCorrelationId`, _, id) => id
      }
      clientConnectionTestProbe.send(
        clientConnectionTestProbe.lastSender,
        MessageReceivedConfirmation(zoneDeliveryId)
      )
    }
    "send a create zone response when a zone is created" in {
      val clientConnectionTestProbe = TestProbe()
      val zoneId = ZoneId.generate
      val sequenceNumber = commandSequenceNumbers(zoneId)
      commandSequenceNumbers = commandSequenceNumbers + (zoneId -> (sequenceNumber + 1))
      clientConnectionTestProbe.send(
        zoneValidatorShardRegion,
        EnvelopedMessage(
          zoneId,
          AuthenticatedCommandWithIds(
            publicKey,
            CreateZoneCommand(
              publicKey,
              Some("Dave"),
              None,
              None,
              None,
              Some("Dave's Game")
            ),
            unusedClientCorrelationId,
            sequenceNumber,
            unusedClientDeliveryId
          )
        )
      )
      clientConnectionTestProbe.expectMsgPF(10.seconds) {
        case CommandReceivedConfirmation(`zoneId`, _) =>
      }
      val zoneDeliveryId = clientConnectionTestProbe.expectMsgPF() {
        case ResponseWithIds(Right(CreateZoneResponse(_)), `unusedClientCorrelationId`, _, id) => id
      }
      clientConnectionTestProbe.send(
        clientConnectionTestProbe.lastSender,
        MessageReceivedConfirmation(zoneDeliveryId)
      )
    }
    "send a JoinZoneResponse when joined" in {
      val clientConnectionTestProbe = TestProbe()
      val zoneId = ZoneId.generate
      locally {
        val sequenceNumber = commandSequenceNumbers(zoneId)
        commandSequenceNumbers = commandSequenceNumbers + (zoneId -> (sequenceNumber + 1))
        clientConnectionTestProbe.send(
          zoneValidatorShardRegion,
          EnvelopedMessage(
            zoneId,
            AuthenticatedCommandWithIds(
              publicKey,
              CreateZoneCommand(
                publicKey,
                Some("Dave"),
                None,
                None,
                None,
                Some("Dave's Game")
              ),
              unusedClientCorrelationId,
              sequenceNumber,
              unusedClientDeliveryId
            )
          )
        )
        clientConnectionTestProbe.expectMsgPF(10.seconds) {
          case CommandReceivedConfirmation(`zoneId`, _) =>
        }
        val zoneDeliveryId = clientConnectionTestProbe.expectMsgPF() {
          case ResponseWithIds(Right(CreateZoneResponse(_)), `unusedClientCorrelationId`, _, id) => id
        }
        clientConnectionTestProbe.send(
          clientConnectionTestProbe.lastSender,
          MessageReceivedConfirmation(zoneDeliveryId)
        )
      }
      locally {
        val sequenceNumber = commandSequenceNumbers(zoneId)
        commandSequenceNumbers = commandSequenceNumbers + (zoneId -> (sequenceNumber + 1))
        clientConnectionTestProbe.send(
          zoneValidatorShardRegion, AuthenticatedCommandWithIds(
            publicKey,
            JoinZoneCommand(
              zoneId
            ),
            unusedClientCorrelationId,
            sequenceNumber,
            unusedClientDeliveryId
          )
        )
        clientConnectionTestProbe.expectMsgPF(10.seconds) {
          case CommandReceivedConfirmation(`zoneId`, _) =>
        }
        val expectedConnectedClients = Set(publicKey)
        val zoneDeliveryId = clientConnectionTestProbe.expectMsgPF() {
          case ResponseWithIds(Right(JoinZoneResponse(_, connectedClients)), `unusedClientCorrelationId`, _, id)
            if connectedClients == expectedConnectedClients => id
        }
        clientConnectionTestProbe.send(
          clientConnectionTestProbe.lastSender,
          MessageReceivedConfirmation(zoneDeliveryId)
        )
      }
    }
  }
}
