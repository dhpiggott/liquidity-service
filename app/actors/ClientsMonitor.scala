package actors

import actors.ClientsMonitor._
import akka.actor._
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Subscribe
import com.dhpcs.liquidity.models.PublicKey

object ClientsMonitor {

  def props = Props(new ClientsMonitor)

  val Topic = "clients"

  case class ActiveClientSummary(publicKey: PublicKey)

  case class ActiveClientsSummary(activeClientSummaries: Seq[ActiveClientSummary])

  case object GetActiveClientsSummary

}

class ClientsMonitor extends Actor with ActorLogging {

  private val mediator = DistributedPubSub(context.system).mediator

  private var activeClientSummaries = Map.empty[ActorRef, ActiveClientSummary]

  mediator ! Subscribe(ClientsMonitor.Topic, self)

  override def receive = {

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
