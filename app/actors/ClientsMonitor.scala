package actors

import actors.ClientsMonitor._
import akka.actor._
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Subscribe
import com.dhpcs.liquidity.models.PublicKey

import scala.concurrent.duration._

object ClientsMonitor {

  def props = Props(new ClientsMonitor)

  val Topic = "clients"

  case class ActiveClientSummary(publicKey: PublicKey)

  case class ActiveClientsSummary(activeClientSummaries: Seq[ActiveClientSummary])

  case object GetActiveClientsSummary

  private case object PublishStatus

}

class ClientsMonitor extends Actor with ActorLogging {

  import context.dispatcher

  private val mediator = DistributedPubSub(context.system).mediator

  private val publishStatusTick = context.system.scheduler.schedule(0.minutes, 5.minutes, self, PublishStatus)

  private var activeClientSummaries = Map.empty[ActorRef, ActiveClientSummary]

  mediator ! Subscribe(ClientsMonitor.Topic, self)

  override def postStop() {
    publishStatusTick.cancel()
    super.postStop()
  }

  override def receive = {

    case PublishStatus =>

      log.info(s"${activeClientSummaries.size} clients are active")

    case activeClientSummary: ActiveClientSummary =>

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
