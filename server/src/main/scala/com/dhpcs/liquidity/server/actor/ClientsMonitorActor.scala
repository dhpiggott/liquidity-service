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

object ClientsMonitorActor {

  final val ClientStatusTopic = "Client"

  private case object LogActiveClientsCountTimerKey

  def behaviour: Behavior[ClientsMonitorMessage] = Actor.deferred { context =>
    val log      = Logging(context.system.toUntyped, context.self.toUntyped)
    val mediator = DistributedPubSub(context.system.toUntyped).mediator
    mediator ! Subscribe(ClientStatusTopic, context.self.toUntyped)
    Actor.withTimers { timers =>
      timers.startPeriodicTimer(LogActiveClientsCountTimerKey, LogActiveClientsCount, 5.minutes)
      withSummaries(log, Map.empty)
    }
  }

  private def withSummaries(
      log: LoggingAdapter,
      activeClientSummaries: Map[ActorRef, ActiveClientSummary]): Behavior[ClientsMonitorMessage] =
    Actor.immutable[ClientsMonitorMessage] { (context, message) =>
      message match {
        case UpsertActiveClientSummary(clientConnectionActorRef, activeClientSummary) =>
          if (!activeClientSummaries.contains(clientConnectionActorRef))
            context.watch(clientConnectionActorRef)
          withSummaries(log, activeClientSummaries + (clientConnectionActorRef -> activeClientSummary))

        case LogActiveClientsCount =>
          log.info(s"${activeClientSummaries.size} clients are active")
          Actor.same

        case GetActiveClientSummaries(replyTo) =>
          replyTo ! activeClientSummaries.values.toSet
          Actor.same
      }
    } onSignal {
      case (_, Terminated(ref)) =>
        withSummaries(log, activeClientSummaries - ref.toUntyped)
    }

}
