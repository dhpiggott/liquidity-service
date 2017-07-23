package com.dhpcs.liquidity.server.actor

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Subscribe
import com.dhpcs.liquidity.actor.protocol._
import com.dhpcs.liquidity.server.actor.ZonesMonitorActor._

import scala.concurrent.duration._

// TODO: Switch to akka-typed
object ZonesMonitorActor {

  def props: Props = Props(new ZonesMonitorActor)

  case object GetActiveZonesSummary
  final case class ActiveZonesSummary(activeZoneSummaries: Set[ActiveZoneSummary])

  private case object PublishStatus

}

class ZonesMonitorActor extends Actor with ActorLogging {

  import context.dispatcher

  private[this] val mediator = DistributedPubSub(context.system).mediator
  mediator ! Subscribe(ZoneStatusTopic, self)

  private[this] val publishStatusTick = context.system.scheduler.schedule(0.minutes, 5.minutes, self, PublishStatus)

  private[this] var activeZoneSummaries = Map.empty[ActorRef, ActiveZoneSummary]

  override def postStop(): Unit = {
    publishStatusTick.cancel()
    super.postStop()
  }

  override def receive: Receive = {
    case PublishStatus =>
      log.info(s"${activeZoneSummaries.size} zones are active")

    case activeZoneSummary: ActiveZoneSummary =>
      if (!activeZoneSummaries.contains(sender()))
        context.watch(sender())
      activeZoneSummaries = activeZoneSummaries + (sender() -> activeZoneSummary)

    case GetActiveZonesSummary =>
      sender() ! ActiveZonesSummary(activeZoneSummaries.values.toSet)

    case Terminated(zoneValidator) =>
      activeZoneSummaries = activeZoneSummaries - zoneValidator
  }
}
