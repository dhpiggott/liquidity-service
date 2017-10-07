package com.dhpcs.liquidity.server.actor

import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Subscribe
import akka.event.{Logging, LoggingAdapter}
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.adapter._
import akka.typed.{ActorRef, Behavior, Terminated}
import com.dhpcs.liquidity.actor.protocol.zonemonitor._
import com.dhpcs.liquidity.actor.protocol.zonevalidator.ZoneValidatorMessage

import scala.concurrent.duration._

object ZoneMonitorActor {

  final val ZoneStatusTopic = "Zone"

  private case object LogActiveZonesCountTimerKey

  def behavior: Behavior[ZoneMonitorMessage] =
    Actor.deferred(context =>
      Actor.withTimers { timers =>
        val log      = Logging(context.system.toUntyped, context.self.toUntyped)
        val mediator = DistributedPubSub(context.system.toUntyped).mediator
        mediator ! Subscribe(ZoneStatusTopic, context.self.toUntyped)
        timers.startPeriodicTimer(LogActiveZonesCountTimerKey, LogActiveZonesCount, 5.minutes)
        withSummaries(log, Map.empty)
    })

  private def withSummaries(
      log: LoggingAdapter,
      activeZoneSummaries: Map[ActorRef[ZoneValidatorMessage], ActiveZoneSummary]): Behavior[ZoneMonitorMessage] =
    Actor.immutable[ZoneMonitorMessage]((context, message) =>
      message match {
        case LogActiveZonesCount =>
          log.info(s"${activeZoneSummaries.size} zones are active")
          Actor.same

        case GetActiveZoneSummaries(replyTo) =>
          replyTo ! activeZoneSummaries.values.toSet
          Actor.same

        case UpsertActiveZoneSummary(zoneValidatorActorRef, activeZoneSummary) =>
          if (!activeZoneSummaries.contains(zoneValidatorActorRef))
            context.watch(zoneValidatorActorRef)
          withSummaries(log, activeZoneSummaries + (zoneValidatorActorRef -> activeZoneSummary))
    }) onSignal {
      case (_, Terminated(ref)) =>
        withSummaries(log, activeZoneSummaries - ref.toUntyped)
    }

}
