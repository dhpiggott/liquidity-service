package actors

import java.security.KeyPairGenerator
import java.util.UUID

import actors.ClientConnection.MessageReceivedConfirmation
import actors.ZoneValidator.{AuthenticatedCommandWithIds, CommandReceivedConfirmation, EnvelopedMessage, ResponseWithIds}
import akka.actor.ActorSystem
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.testkit.{DefaultTimeout, TestKit, TestProbe}
import com.dhpcs.jsonrpc.{ErrorResponse, JsonRpcResponseError}
import com.dhpcs.liquidity.models._
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}

import scala.concurrent.duration._
import scala.util.Left

class ZoneValidatorSpec(system: ActorSystem) extends TestKit(system)
  with WordSpecLike with DefaultTimeout with BeforeAndAfterAll {

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

  private implicit val sys = system

  private val unusedClientCorrelationId = Option(Left("unused"))
  private val unusedClientDeliveryId = 0L

  private var commandSequenceNumbers = Map.empty[ZoneId, Long].withDefaultValue(0L)

  def this() = this(ActorSystem("application"))

  override def afterAll() = TestKit.shutdownActorSystem(system)

  "A ZoneValidator" must {
    "send an error response when joined before creation" in {
      val testProbe = TestProbe()
      val zoneId = ZoneId.generate
      val sequenceNumber = commandSequenceNumbers(zoneId)
      commandSequenceNumbers = commandSequenceNumbers + (zoneId -> (sequenceNumber + 1))
      testProbe.send(
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
      testProbe.expectMsgPF(10.seconds) {
        case CommandReceivedConfirmation(`zoneId`, _) =>
      }
      val expectedResponse = Left(
        ErrorResponse(
          JsonRpcResponseError.ReservedErrorCodeFloor - 1,
          "Zone does not exist"
        )
      )
      val zoneDeliveryId = testProbe.expectMsgPF() {
        case ResponseWithIds(`expectedResponse`, `unusedClientCorrelationId`, _, id) => id
      }
      testProbe.send(
        testProbe.lastSender,
        MessageReceivedConfirmation(zoneDeliveryId)
      )
    }
    "send a create zone response when a zone is created" in {
      val testProbe = TestProbe()
      val zoneId = ZoneId.generate
      val sequenceNumber = commandSequenceNumbers(zoneId)
      commandSequenceNumbers = commandSequenceNumbers + (zoneId -> (sequenceNumber + 1))
      testProbe.send(
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
      testProbe.expectMsgPF(10.seconds) {
        case CommandReceivedConfirmation(`zoneId`, _) =>
      }
      val zoneDeliveryId = testProbe.expectMsgPF() {
        case ResponseWithIds(Right(CreateZoneResponse(_)), `unusedClientCorrelationId`, _, id) => id
      }
      testProbe.send(
        testProbe.lastSender,
        MessageReceivedConfirmation(zoneDeliveryId)
      )
    }
    "send a JoinZoneResponse when joined" in {
      val testProbe = TestProbe()
      val zoneId = ZoneId(UUID.fromString("4cdcdb95-5647-4d46-a2f9-a68e9294d00a"))
      val sequenceNumber = commandSequenceNumbers(zoneId)
      commandSequenceNumbers = commandSequenceNumbers + (zoneId -> (sequenceNumber + 1))
      testProbe.send(
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
      testProbe.expectMsgPF(10.seconds) {
        case CommandReceivedConfirmation(`zoneId`, _) =>
      }
      val expectedConnectedClients = Set(publicKey)
      val zoneDeliveryId = testProbe.expectMsgPF() {
        case ResponseWithIds(Right(JoinZoneResponse(_, `expectedConnectedClients`)), `unusedClientCorrelationId`, _, id) => id
      }
      testProbe.send(
        testProbe.lastSender,
        MessageReceivedConfirmation(zoneDeliveryId)
      )
    }
  }

}