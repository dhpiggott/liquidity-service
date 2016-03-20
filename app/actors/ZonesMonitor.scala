package actors

import java.util.UUID

import actors.ZonesMonitor._
import akka.actor._
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Subscribe
import akka.pattern.pipe
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.ActorMaterializer
import com.dhpcs.liquidity.models._
import play.api.libs.json.JsObject

import scala.concurrent.duration._

object ZonesMonitor {

  def props = Props(new ZonesMonitor)

  val Topic = "zones"

  case object GetActiveZonesSummary

  case class ActiveZoneSummary(zoneId: ZoneId,
                               metadata: Option[JsObject],
                               members: Set[Member],
                               accounts: Set[Account],
                               transactions: Set[Transaction],
                               clientConnections: Set[PublicKey])

  case class ActiveZonesSummary(activeZoneSummaries: Set[ActiveZoneSummary])

  case object GetZoneCount

  case class ZoneCount(count: Int)

  private case object PublishStatus

  private val ZoneIdStringPattern = """ZoneId\(([0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\)""".r

}

class ZonesMonitor extends Actor with ActorLogging {

  import context.dispatcher

  private val mediator = DistributedPubSub(context.system).mediator

  private val publishStatusTick = context.system.scheduler.schedule(0.minutes, 5.minutes, self, PublishStatus)

  private var activeZoneSummaries = Map.empty[ActorRef, ActiveZoneSummary]

  mediator ! Subscribe(Topic, self)

  private implicit val materializer = ActorMaterializer()

  private val readJournal = PersistenceQuery(context.system)
    .readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

  override def postStop() {
    publishStatusTick.cancel()
    super.postStop()
  }

  override def receive = {

    case PublishStatus =>

      log.info(s"${activeZoneSummaries.size} zones are active")

    case activeZoneSummary: ActiveZoneSummary =>

      if (!activeZoneSummaries.contains(sender())) {

        context.watch(sender())

      }

      activeZoneSummaries = activeZoneSummaries + (sender -> activeZoneSummary)

    case GetZoneCount =>

      val requester = sender()

      readJournal.currentPersistenceIds()
        .collect { case ZoneIdStringPattern(zoneIdString) =>
          ZoneId(UUID.fromString(zoneIdString))
        }
        .runFold(0)((count, _) => count + 1)
        .map(ZoneCount)
        .pipeTo(requester)

    case GetActiveZonesSummary =>

      sender ! ActiveZonesSummary(activeZoneSummaries.values.toSet)

    case Terminated(zoneValidator) =>

      activeZoneSummaries = activeZoneSummaries - zoneValidator

  }

}
