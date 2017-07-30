package com.dhpcs.liquidity.server.actor

import akka.actor.ActorRef
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Subscribe
import akka.event.{Logging, LoggingAdapter}
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.adapter._
import akka.typed.{Behavior, Terminated}
import com.dhpcs.liquidity.actor.protocol._

import scala.concurrent.duration._

object ZonesMonitorActor {

  private case object LogActiveZonesCountTimerKey

  def behaviour: Behavior[ZonesMonitorMessage] = Actor.deferred { context =>
    val log      = Logging(context.system.toUntyped, context.self.toUntyped)
    val mediator = DistributedPubSub(context.system.toUntyped).mediator
    mediator ! Subscribe(ZoneStatusTopic, context.self.toUntyped)
    Actor.withTimers { timers =>
      timers.startPeriodicTimer(LogActiveZonesCountTimerKey, LogActiveZonesCount, 5.minutes)
      withSummaries(log, Map.empty)
    }
  }

  private def withSummaries(log: LoggingAdapter,
                            activeZoneSummaries: Map[ActorRef, ActiveZoneSummary]): Behavior[ZonesMonitorMessage] =
    Actor.immutable[ZonesMonitorMessage] { (context, message) =>
      message match {
        case UpsertActiveZoneSummary(zoneValidatorActorRef, activeZoneSummary) =>
          if (!activeZoneSummaries.contains(zoneValidatorActorRef))
            context.watch(zoneValidatorActorRef)
          withSummaries(log, activeZoneSummaries + (zoneValidatorActorRef -> activeZoneSummary))

        case LogActiveZonesCount =>
          log.info(s"${activeZoneSummaries.size} zones are active")
          Actor.same

        case GetActiveZoneSummaries(replyTo) =>
          replyTo ! activeZoneSummaries.values.toSet
          Actor.same
      }
    } onSignal {
      case (_, Terminated(ref)) =>
        withSummaries(log, activeZoneSummaries - ref.toUntyped)
    }

}
