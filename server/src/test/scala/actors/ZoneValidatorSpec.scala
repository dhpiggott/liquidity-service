package actors

import java.security.KeyPairGenerator

import actors.ClientConnection.MessageReceivedConfirmation
import actors.ZoneValidator.{AuthenticatedCommandWithIds, CommandReceivedConfirmation, EnvelopedMessage, ResponseWithIds}
import akka.testkit.TestProbe
import com.dhpcs.jsonrpc.JsonRpcResponseError
import com.dhpcs.jsonrpc.ResponseCompanion.ErrorResponse
import com.dhpcs.liquidity.models._
import org.scalatest.EitherValues._
import org.scalatest.{Inside, Matchers, WordSpec}

import scala.concurrent.duration._
import scala.util.Left

class ZoneValidatorSpec extends WordSpec with Inside with Matchers with ZoneValidatorShardRegionProvider {
  private[this] val publicKey = {
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    PublicKey(publicKeyBytes)
  }

  private[this] val unusedClientCorrelationId = Option(Left("unused"))
  private[this] val unusedClientDeliveryId = 0L

  "A ZoneValidator" must {
    "send an error response when joined before creation" in {
      val (clientConnectionTestProbe, zoneId, sequenceNumber) = setup()
      send(clientConnectionTestProbe, zoneId)(
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
      expectError(clientConnectionTestProbe)(error =>
        error shouldBe ErrorResponse(JsonRpcResponseError.ReservedErrorCodeFloor - 1, "Zone does not exist")
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
      expectResult(clientConnectionTestProbe)(result => result should matchPattern {
        case CreateZoneResponse(_) =>
      })
    }
    "send a JoinZoneResponse when joined" in {
      val (clientConnectionTestProbe, zoneId, sequenceNumber) = setup()
      send(clientConnectionTestProbe, zoneId)(
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
      expectResult(clientConnectionTestProbe)(result => result should matchPattern {
        case CreateZoneResponse(_) =>
      })
      send(clientConnectionTestProbe, zoneId)(
        AuthenticatedCommandWithIds(
          publicKey,
          JoinZoneCommand(
            zoneId
          ),
          unusedClientCorrelationId,
          sequenceNumber + 1,
          unusedClientDeliveryId
        )
      )
      expectResult(clientConnectionTestProbe)(result => result should matchPattern {
        case JoinZoneResponse(_, connectedClients) if connectedClients == Set(publicKey) =>
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
    clientConnectionTestProbe.expectMsgPF(5.seconds) {
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
      case ResponseWithIds(response, `unusedClientCorrelationId`, _, id) => f(response); id
    }
    clientConnectionTestProbe.send(
      clientConnectionTestProbe.lastSender,
      MessageReceivedConfirmation(zoneDeliveryId)
    )
  }
}
