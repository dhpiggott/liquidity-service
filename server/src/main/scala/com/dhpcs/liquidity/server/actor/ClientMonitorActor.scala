package com.dhpcs.liquidity.server.actor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Subscribe
import com.dhpcs.liquidity.actor.protocol.clientmonitor._

import scala.concurrent.duration._

object ClientMonitorActor {

  final val ClientStatusTopic = "Client"

  private[this] case object LogActiveClientsCountTimerKey

  def behavior: Behavior[ClientMonitorMessage] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        val mediator = DistributedPubSub(context.system.toUntyped).mediator
        mediator ! Subscribe(ClientStatusTopic, context.self.toUntyped)
        timers.startPeriodicTimer(LogActiveClientsCountTimerKey,
                                  LogActiveClientsCount,
                                  5.minutes)
        withSummaries(Map.empty)
      }
    }

  private[this] def withSummaries(
      activeClientSummaries: Map[ActorRef[Nothing], ActiveClientSummary])
    : Behavior[ClientMonitorMessage] =
    Behaviors.immutable[ClientMonitorMessage]((context, message) =>
      message match {
        case LogActiveClientsCount =>
          context.log.info(s"${activeClientSummaries.size} clients are active")
          Behaviors.same

        case GetActiveClientSummaries(replyTo) =>
          replyTo ! activeClientSummaries.values.toSet
          Behaviors.same

        case UpsertActiveClientSummary(clientConnection, activeClientSummary) =>
          if (!activeClientSummaries.contains(clientConnection))
            context.watchWith(clientConnection,
                              DeleteActiveClientSummary(clientConnection))
          withSummaries(
            activeClientSummaries + (clientConnection -> activeClientSummary))

        case DeleteActiveClientSummary(clientConnection) =>
          withSummaries(activeClientSummaries - clientConnection)
    })

}
