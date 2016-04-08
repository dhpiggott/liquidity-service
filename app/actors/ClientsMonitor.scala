package actors

import actors.ClientsMonitor._
import akka.actor._
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{Subscribe, Unsubscribe}

import scala.concurrent.duration._

object ClientsMonitor {

  def props = Props(new ClientsMonitor)

  case class ActiveClientsSummary(activeClientSummaries: Seq[ClientConnection.ActiveClientSummary])

  case object GetActiveClientsSummary

  private case object PublishStatus

}

class ClientsMonitor extends Actor with ActorLogging {

  import context.dispatcher

  private val mediator = DistributedPubSub(context.system).mediator

  private val publishStatusTick = context.system.scheduler.schedule(0.minutes, 5.minutes, self, PublishStatus)

  private var activeClientSummaries = Map.empty[ActorRef, ClientConnection.ActiveClientSummary]

  mediator ! Subscribe(ClientConnection.Topic, self)

  override def postStop() {
    publishStatusTick.cancel()
    mediator ! Unsubscribe(ClientConnection.Topic, self)
    super.postStop()
  }

  override def receive = {

    case PublishStatus =>

      log.info(s"${activeClientSummaries.size} clients are active")

    case activeClientSummary: ClientConnection.ActiveClientSummary =>

      if (!activeClientSummaries.contains(sender())) {

        context.watch(sender())

      }

      activeClientSummaries = activeClientSummaries + (sender -> activeClientSummary)

    case GetActiveClientsSummary =>

      sender ! ActiveClientsSummary(activeClientSummaries.values.toSeq)

    case Terminated(clientConnection) =>

      activeClientSummaries = activeClientSummaries - clientConnection

  }

}
