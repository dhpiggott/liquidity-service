package com.dhpcs.liquidity.server.actor

import akka.actor.{Actor, ActorLogging, ActorRef, NoSerializationVerificationNeeded, Props, Terminated}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Subscribe
import com.dhpcs.liquidity.actor.protocol._
import com.dhpcs.liquidity.server.actor.ClientsMonitorActor._

import scala.concurrent.duration._

object ClientsMonitorActor {

  def props: Props = Props[ClientsMonitorActor]

  final case class ActiveClientsSummary(activeClientSummaries: Seq[ActiveClientSummary])
      extends NoSerializationVerificationNeeded

  case object GetActiveClientsSummary extends NoSerializationVerificationNeeded

  private case object PublishStatus extends NoSerializationVerificationNeeded

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
      sender() ! ActiveClientsSummary(activeClientSummaries.values.toSeq)
    case Terminated(clientConnection) =>
      activeClientSummaries = activeClientSummaries - clientConnection
  }
}
