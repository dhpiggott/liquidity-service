package actors

import java.security.KeyPairGenerator

import actors.ClientConnection.MessageReceivedConfirmation
import actors.ZoneValidator.{AuthenticatedCommandWithIds, CommandReceivedConfirmation, EnvelopedMessage, ResponseWithIds}
import akka.testkit.TestProbe
import com.dhpcs.jsonrpc.JsonRpcResponseError
import com.dhpcs.jsonrpc.ResponseCompanion.ErrorResponse
import com.dhpcs.liquidity.models._
import org.scalatest.EitherValues._
import org.scalatest.{Inside, MustMatchers, WordSpec}

import scala.util.Left

class ZoneValidatorSpec extends WordSpec
  with Inside with MustMatchers with ZoneValidatorShardRegionProvider {
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
            unusedClientCorrelationId,
            sequenceNumber,
            unusedClientDeliveryId
          )
        )
      )
      expectResult(clientConnectionTestProbe)(result =>
        result mustBe a[CreateZoneResponse]
      )
    }
    "send a JoinZoneResponse when joined" in {
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
            unusedClientCorrelationId,
            sequenceNumber,
            unusedClientDeliveryId
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
          unusedClientCorrelationId,
          sequenceNumber + 1,
          unusedClientDeliveryId
        )
      )
      expectResult(clientConnectionTestProbe)(result => inside(result){
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
      case ResponseWithIds(response, `unusedClientCorrelationId`, _, id) => f(response); id
    }
    clientConnectionTestProbe.send(
      clientConnectionTestProbe.lastSender,
      MessageReceivedConfirmation(zoneDeliveryId)
    )
  }
}
