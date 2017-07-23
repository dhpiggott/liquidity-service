package com.dhpcs.liquidity.server.actor

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Subscribe
import com.dhpcs.liquidity.actor.protocol._
import com.dhpcs.liquidity.server.actor.ClientsMonitorActor._

import scala.collection.immutable.Seq
import scala.concurrent.duration._

// TODO: Switch to akka-typed
object ClientsMonitorActor {

  def props: Props = Props(new ClientsMonitorActor)

  case object GetActiveClientsSummary
  final case class ActiveClientsSummary(activeClientSummaries: Seq[ActiveClientSummary])

  private case object PublishStatus

}

class ClientsMonitorActor extends Actor with ActorLogging {

  import context.dispatcher

  private[this] val mediator = DistributedPubSub(context.system).mediator
  mediator ! Subscribe(ClientStatusTopic, self)

  private[this] val publishStatusTick = context.system.scheduler.schedule(0.minutes, 5.minutes, self, PublishStatus)

  private[this] var activeClientSummaries = Map.empty[ActorRef, ActiveClientSummary]

  override def postStop(): Unit = {
    publishStatusTick.cancel()
    super.postStop()
  }

  override def receive: Receive = {
    case PublishStatus =>
      log.info(s"${activeClientSummaries.size} clients are active")

    case activeClientSummary: ActiveClientSummary =>
      if (!activeClientSummaries.contains(sender()))
        context.watch(sender())
      activeClientSummaries = activeClientSummaries + (sender() -> activeClientSummary)

    case GetActiveClientsSummary =>
      sender() ! ActiveClientsSummary(activeClientSummaries.values.to[Seq])

    case Terminated(clientConnection) =>
      activeClientSummaries = activeClientSummaries - clientConnection
  }
}
