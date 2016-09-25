package com.dhpcs.liquidity.server.actors

import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Subscribe
import akka.pattern.pipe
import akka.persistence.query.scaladsl.{CurrentPersistenceIdsQuery, ReadJournal}
import akka.stream.ActorMaterializer
import com.dhpcs.liquidity.model.ZoneId
import com.dhpcs.liquidity.persistence.ZoneIdStringPattern
import com.dhpcs.liquidity.server.actors.ZonesMonitorActor._

import scala.concurrent.duration._

object ZonesMonitorActor {

  def props(readJournal: ReadJournal with CurrentPersistenceIdsQuery): Props = Props(new ZonesMonitorActor(readJournal))

  case object GetActiveZonesSummary

  case class ActiveZonesSummary(activeZoneSummaries: Set[ZoneValidatorActor.ActiveZoneSummary])

  case object GetZoneCount

  case class ZoneCount(count: Int)

  private case object PublishStatus

}

class ZonesMonitorActor(readJournal: ReadJournal with CurrentPersistenceIdsQuery) extends Actor with ActorLogging {

  import context.dispatcher

  private[this] implicit val mat = ActorMaterializer()

  private[this] val mediator = DistributedPubSub(context.system).mediator
  mediator ! Subscribe(ZoneValidatorActor.Topic, self)

  private[this] val publishStatusTick = context.system.scheduler.schedule(0.minutes, 5.minutes, self, PublishStatus)

  private[this] var activeZoneSummaries = Map.empty[ActorRef, ZoneValidatorActor.ActiveZoneSummary]

  override def postStop(): Unit = {
    publishStatusTick.cancel()
    super.postStop()
  }

  override def receive: Receive = {
    case PublishStatus =>
      log.info(s"${activeZoneSummaries.size} zones are active")
    case activeZoneSummary: ZoneValidatorActor.ActiveZoneSummary =>
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
