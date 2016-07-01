package actors

import java.util.UUID

import actors.ZonesMonitor._
import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{Subscribe, Unsubscribe}
import akka.pattern.pipe
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.ActorMaterializer
import com.dhpcs.liquidity.protocol.ZoneId

import scala.concurrent.duration._

object ZonesMonitor {
  def props: Props = Props(new ZonesMonitor)

  case object GetActiveZonesSummary

  case class ActiveZonesSummary(activeZoneSummaries: Set[ZoneValidator.ActiveZoneSummary])

  case object GetZoneCount

  case class ZoneCount(count: Int)

  private case object PublishStatus

  private final val ZoneIdStringPattern =
    """ZoneId\(([0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\)""".r
}

class ZonesMonitor extends Actor with ActorLogging {

  import context.dispatcher

  private[this] implicit val mat = ActorMaterializer()

  private[this] val readJournal = PersistenceQuery(context.system)
    .readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
  private[this] val mediator = DistributedPubSub(context.system).mediator
  private[this] val publishStatusTick = context.system.scheduler.schedule(0.minutes, 5.minutes, self, PublishStatus)

  private[this] var activeZoneSummaries = Map.empty[ActorRef, ZoneValidator.ActiveZoneSummary]

  override def preStart(): Unit = {
    super.preStart()
    mediator ! Subscribe(ZoneValidator.Topic, self)
  }

  override def postStop(): Unit = {
    publishStatusTick.cancel()
    mediator ! Unsubscribe(ZoneValidator.Topic, self)
    super.postStop()
  }

  override def receive: Receive = {
    case PublishStatus =>
      log.info(s"${activeZoneSummaries.size} zones are active")
    case activeZoneSummary: ZoneValidator.ActiveZoneSummary =>
      if (!activeZoneSummaries.contains(sender())) {
        context.watch(sender())
      }
      activeZoneSummaries = activeZoneSummaries + (sender() -> activeZoneSummary)
    case GetZoneCount =>
      val requester = sender()
      readJournal.currentPersistenceIds
        .collect { case ZoneIdStringPattern(uuidString) =>
          ZoneId(UUID.fromString(uuidString))
        }
        .runFold(0)((count, _) => count + 1)
        .map(ZoneCount)
        .pipeTo(requester)
    case GetActiveZonesSummary =>
      sender() ! ActiveZonesSummary(activeZoneSummaries.values.toSet)
    case Terminated(zoneValidator) =>
      activeZoneSummaries = activeZoneSummaries - zoneValidator
  }
}
