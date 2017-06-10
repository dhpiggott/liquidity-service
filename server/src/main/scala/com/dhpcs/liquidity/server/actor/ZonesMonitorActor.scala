package com.dhpcs.liquidity.server.actor

import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Subscribe
import akka.pattern.pipe
import akka.persistence.query.scaladsl.{CurrentPersistenceIdsQuery, ReadJournal}
import akka.stream.Materializer
import com.dhpcs.liquidity.actor.protocol._
import com.dhpcs.liquidity.model.ZoneId
import com.dhpcs.liquidity.persistence.ZoneIdStringPattern
import com.dhpcs.liquidity.server.actor.ZonesMonitorActor._

import scala.concurrent.Future
import scala.concurrent.duration._

object ZonesMonitorActor {

  def props(zoneCount: () => Future[Int]): Props = Props(classOf[ZonesMonitorActor], zoneCount)

  def zoneCount(readJournal: ReadJournal with CurrentPersistenceIdsQuery)(implicit mat: Materializer): Future[Int] =
    readJournal.currentPersistenceIds
      .collect {
        case ZoneIdStringPattern(uuidString) =>
          ZoneId(UUID.fromString(uuidString))
      }
      .runFold(0)((count, _) => count + 1)

  case object GetActiveZonesSummary

  final case class ActiveZonesSummary(activeZoneSummaries: Set[ActiveZoneSummary])

  case object GetZoneCount

  final case class ZoneCount(count: Int)

  private case object PublishStatus

}

class ZonesMonitorActor(zoneCount: () => Future[Int]) extends Actor with ActorLogging {

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
      if (!activeZoneSummaries.contains(sender())) {
        context.watch(sender())
      }
      activeZoneSummaries = activeZoneSummaries + (sender() -> activeZoneSummary)
    case GetZoneCount =>
      val requester = sender()
      zoneCount().map(ZoneCount).pipeTo(requester); ()
    case GetActiveZonesSummary =>
      sender() ! ActiveZonesSummary(activeZoneSummaries.values.toSet)
    case Terminated(zoneValidator) =>
      activeZoneSummaries = activeZoneSummaries - zoneValidator
  }
}
