package com.dhpcs.liquidity.server.actor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, Behavior, Terminated}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Subscribe
import akka.event.{Logging, LoggingAdapter}
import com.dhpcs.liquidity.actor.protocol.clientmonitor._

import scala.concurrent.duration._

object ClientMonitorActor {

  final val ClientStatusTopic = "Client"

  private case object LogActiveClientsCountTimerKey

  def behavior: Behavior[ClientMonitorMessage] =
    Behaviors.setup(context =>
      Behaviors.withTimers { timers =>
        val log = Logging(context.system.toUntyped, context.self.toUntyped)
        val mediator = DistributedPubSub(context.system.toUntyped).mediator
        mediator ! Subscribe(ClientStatusTopic, context.self.toUntyped)
        timers.startPeriodicTimer(LogActiveClientsCountTimerKey,
                                  LogActiveClientsCount,
                                  5.minutes)
        withSummaries(log, Map.empty)
    })

  private def withSummaries(
      log: LoggingAdapter,
      activeClientSummaries: Map[ActorRef[Nothing], ActiveClientSummary])
    : Behavior[ClientMonitorMessage] =
    Behaviors.immutable[ClientMonitorMessage]((context, message) =>
      message match {
        case LogActiveClientsCount =>
          log.info(s"${activeClientSummaries.size} clients are active")
          Behaviors.same

        case GetActiveClientSummaries(replyTo) =>
          replyTo ! activeClientSummaries.values.toSet
          Behaviors.same
        case UpsertActiveClientSummary(clientConnection, activeClientSummary) =>
          if (!activeClientSummaries.contains(clientConnection))
            context.watch(clientConnection)
          withSummaries(
            log,
            activeClientSummaries + (clientConnection -> activeClientSummary))
    }) onSignal {
      case (_, Terminated(ref)) =>
        withSummaries(log, activeClientSummaries - ref)
    }

}
