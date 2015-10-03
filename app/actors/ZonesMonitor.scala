package actors

import actors.ZonesMonitor._
import akka.actor._
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Subscribe
import com.dhpcs.liquidity.models._
import play.api.libs.json.JsObject

import scala.concurrent.duration._

object ZonesMonitor {

  def props = Props(new ZonesMonitor)

  val Topic = "zones"

  case class ActiveZoneSummary(zoneId: ZoneId,
                               metadata: Option[JsObject],
                               members: Set[Member],
                               accounts: Set[Account],
                               transactions: Set[Transaction],
                               clientConnections: Set[PublicKey])

  case class ActiveZonesSummary(activeZoneSummaries: Set[ActiveZoneSummary])

  case object GetActiveZonesSummary

  private case object PublishStatus

}

class ZonesMonitor extends Actor with ActorLogging {

  import context.dispatcher

  private val mediator = DistributedPubSub(context.system).mediator

  private val publishStatusTick = context.system.scheduler.schedule(0.minutes, 5.minutes, self, PublishStatus)

  private var activeZoneSummaries = Map.empty[ActorRef, ActiveZoneSummary]

  mediator ! Subscribe(Topic, self)

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

    case GetActiveZonesSummary =>

      sender ! ActiveZonesSummary(activeZoneSummaries.values.toSet)

    case Terminated(zoneValidator) =>

      activeZoneSummaries = activeZoneSummaries - zoneValidator

  }

}
