package com.dhpcs.liquidity.server.actor

import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Subscribe
import akka.event.{Logging, LoggingAdapter}
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.adapter._
import akka.typed.{ActorRef, Behavior, Terminated}
import com.dhpcs.liquidity.actor.protocol.clientconnection.ClientConnectionMessage
import com.dhpcs.liquidity.actor.protocol.clientmonitor._

import scala.concurrent.duration._

object ClientMonitorActor {

  final val ClientStatusTopic = "Client"

  private case object LogActiveClientsCountTimerKey

  def behavior: Behavior[ClientMonitorMessage] =
    Actor.deferred(context =>
      Actor.withTimers { timers =>
        val log      = Logging(context.system.toUntyped, context.self.toUntyped)
        val mediator = DistributedPubSub(context.system.toUntyped).mediator
        mediator ! Subscribe(ClientStatusTopic, context.self.toUntyped)
        timers.startPeriodicTimer(LogActiveClientsCountTimerKey, LogActiveClientsCount, 5.minutes)
        withSummaries(log, Map.empty)
    })

  private def withSummaries(log: LoggingAdapter,
                            activeClientSummaries: Map[ActorRef[ClientConnectionMessage], ActiveClientSummary])
    : Behavior[ClientMonitorMessage] =
    Actor.immutable[ClientMonitorMessage]((context, message) =>
      message match {
        case LogActiveClientsCount =>
          log.info(s"${activeClientSummaries.size} clients are active")
          Actor.same

        case GetActiveClientSummaries(replyTo) =>
          replyTo ! activeClientSummaries.values.toSet
          Actor.same
        case UpsertActiveClientSummary(clientConnectionActorRef, activeClientSummary) =>
          if (!activeClientSummaries.contains(clientConnectionActorRef))
            context.watch(clientConnectionActorRef)
          withSummaries(log, activeClientSummaries + (clientConnectionActorRef -> activeClientSummary))
    }) onSignal {
      case (_, Terminated(ref)) =>
        withSummaries(log, activeClientSummaries - ref.toUntyped)
    }

}
