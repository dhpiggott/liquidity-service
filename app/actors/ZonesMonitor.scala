package actors

import actors.ZonesMonitor._
import akka.actor._
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Subscribe
import com.dhpcs.liquidity.models.ZoneId

object ZonesMonitor {

  def props = Props(new ZonesMonitor)

  val Topic = "zones"

  case class ActiveZoneSummary(zoneId: ZoneId)

  case class ActiveZonesSummary(activeZoneSummaries: Set[ActiveZoneSummary])

  case object GetActiveZonesSummary

}

class ZonesMonitor extends Actor with ActorLogging {

  private val mediator = DistributedPubSub(context.system).mediator

  private var activeZoneSummaries = Map.empty[ActorRef, ActiveZoneSummary]

  mediator ! Subscribe(Topic, self)

  override def receive = {

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
