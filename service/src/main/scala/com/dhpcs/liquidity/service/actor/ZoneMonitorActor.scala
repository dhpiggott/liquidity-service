package com.dhpcs.liquidity.service.actor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Subscribe
import com.dhpcs.liquidity.actor.protocol.zonemonitor._

import scala.concurrent.duration._

object ZoneMonitorActor {

  final val ZoneStatusTopic = "Zone"

  private[this] case object LogActiveZonesCountTimerKey

  def behavior: Behavior[ZoneMonitorMessage] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        val mediator = DistributedPubSub(context.system.toUntyped).mediator
        mediator ! Subscribe(ZoneStatusTopic, context.self.toUntyped)
        timers.startPeriodicTimer(LogActiveZonesCountTimerKey,
                                  LogActiveZonesCount,
                                  5.minutes)
        withSummaries(Map.empty)
      }
    }

  private[this] def withSummaries(
      activeZoneSummaries: Map[ActorRef[Nothing], ActiveZoneSummary])
    : Behavior[ZoneMonitorMessage] =
    Behaviors.receive[ZoneMonitorMessage]((context, message) =>
      message match {
        case LogActiveZonesCount =>
          context.log.info(s"${activeZoneSummaries.size} zones are active")
          Behaviors.same

        case GetActiveZoneSummaries(replyTo) =>
          replyTo ! activeZoneSummaries.values.toSet
          Behaviors.same

        case UpsertActiveZoneSummary(zoneValidator, activeZoneSummary) =>
          if (!activeZoneSummaries.contains(zoneValidator))
            context.watchWith(zoneValidator,
                              DeleteActiveZoneSummary(zoneValidator))
          withSummaries(
            activeZoneSummaries + (zoneValidator -> activeZoneSummary))

        case DeleteActiveZoneSummary(zoneValidator) =>
          withSummaries(activeZoneSummaries - zoneValidator)
    })

}
