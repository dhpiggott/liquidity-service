package actors

import java.security.KeyPairGenerator
import java.util.UUID

import actors.ClientConnection.MessageReceivedConfirmation
import actors.ZoneValidator.{AuthenticatedCommandWithIds, CommandReceivedConfirmation, EnvelopedMessage, ResponseWithIds}
import akka.actor.ActorSystem
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.testkit.{DefaultTimeout, ImplicitSender, TestKit}
import com.dhpcs.jsonrpc.{ErrorResponse, JsonRpcResponseError}
import com.dhpcs.liquidity.models._
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}

import scala.concurrent.duration._
import scala.util.Left

class ZoneValidatorSpec(_system: ActorSystem) extends TestKit(_system) with DefaultTimeout with ImplicitSender
  with WordSpecLike with BeforeAndAfterAll {

  private val publicKey = {
    val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
    PublicKey(publicKeyBytes)
  }

  private val zoneValidatorShardRegion = ClusterSharding(system).start(
    typeName = ZoneValidator.shardName,
    entityProps = ZoneValidator.props,
    settings = ClusterShardingSettings(system),
    extractEntityId = ZoneValidator.extractEntityId,
    extractShardId = ZoneValidator.extractShardId
  )

  private val unusedClientCorrelationId = Option(Left("unused"))
  private val unusedClientDeliveryId = 0L

  private var commandSequenceNumbers = Map.empty[ZoneId, Long].withDefaultValue(0L)

  def this() = this(ActorSystem("application"))

  override def afterAll() {
    shutdown()
  }

  "A ZoneValidator" must {
    "send an error response when joined before creation" in {
      val zoneId = ZoneId.generate
      val sequenceNumber = commandSequenceNumbers(zoneId)
      commandSequenceNumbers = commandSequenceNumbers + (zoneId -> (sequenceNumber + 1))
      zoneValidatorShardRegion ! AuthenticatedCommandWithIds(
        publicKey,
        JoinZoneCommand(
          zoneId
        ),
        unusedClientCorrelationId,
        sequenceNumber,
        unusedClientDeliveryId
      )
      expectMsgPF(10.seconds) {
        case CommandReceivedConfirmation(`zoneId`, _) =>
      }
      val expectedResponse = Left(
        ErrorResponse(
          JsonRpcResponseError.ReservedErrorCodeFloor - 1,
          "Zone does not exist"
        )
      )
      val zoneDeliveryId = expectMsgPF() {
        case ResponseWithIds(`expectedResponse`, `unusedClientCorrelationId`, _, id) => id
      }
      lastSender ! MessageReceivedConfirmation(zoneDeliveryId)
    }
  }

  "A ZoneValidator" must {
    "send a create zone response when a zone is created" in {
      val zoneId = ZoneId.generate
      val sequenceNumber = commandSequenceNumbers(zoneId)
      commandSequenceNumbers = commandSequenceNumbers + (zoneId -> (sequenceNumber + 1))
      zoneValidatorShardRegion ! EnvelopedMessage(
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
      expectMsgPF(10.seconds) {
        case CommandReceivedConfirmation(`zoneId`, _) =>
      }
      val zoneDeliveryId = expectMsgPF() {
        case ResponseWithIds(Right(CreateZoneResponse(_)), `unusedClientCorrelationId`, _, id) => id
      }
      lastSender ! MessageReceivedConfirmation(zoneDeliveryId)
    }
  }

  "A ZoneValidator" must {
    "send a JoinZoneResponse when joined" in {
      val zoneId = ZoneId(UUID.fromString("4cdcdb95-5647-4d46-a2f9-a68e9294d00a"))
      val sequenceNumber = commandSequenceNumbers(zoneId)
      commandSequenceNumbers = commandSequenceNumbers + (zoneId -> (sequenceNumber + 1))
      zoneValidatorShardRegion ! AuthenticatedCommandWithIds(
        publicKey,
        JoinZoneCommand(
          zoneId
        ),
        unusedClientCorrelationId,
        sequenceNumber,
        unusedClientDeliveryId
      )
      expectMsgPF(10.seconds) {
        case CommandReceivedConfirmation(`zoneId`, _) =>
      }
      val expectedConnectedClients = Set(publicKey)
      val zoneDeliveryId = expectMsgPF() {
        case ResponseWithIds(Right(JoinZoneResponse(_, `expectedConnectedClients`)), `unusedClientCorrelationId`, _, id) => id
      }
      lastSender ! MessageReceivedConfirmation(zoneDeliveryId)
    }
  }

}