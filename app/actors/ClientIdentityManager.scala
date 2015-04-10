package actors

import actors.ClientConnection.AuthenticatedInboundMessage
import actors.ClientIdentity.{CreateConnection, PostedInboundAuthenticatedMessage}
import actors.ClientIdentityManager.{CreateConnectionForIdentity, TerminationRequest}
import akka.actor._
import controllers.Application.PublicKey

import scala.concurrent.duration._

object ClientIdentityManager {

  val StoppingChildRetryDelay = 100.milliseconds

  def props() = Props[ClientIdentityManager]

  case class CreateConnectionForIdentity(publicKey: PublicKey, remoteAddress: String)

  case object TerminationRequest

}

class ClientIdentityManager extends Actor with ActorLogging {

  import context.dispatcher

  def receive: Receive = receive(Set.empty)

  def receive(stoppingChildren: Set[String]): Receive = {

    case createConnectionForIdentity@CreateConnectionForIdentity(publicKey, remoteAddress) =>

      val childName = publicKey.fingerprint
      if (stoppingChildren.contains(childName)) {

        log.debug(s"Received request for stopping identity; scheduling retry")
        context.system.scheduler.scheduleOnce(
          ClientIdentityManager.StoppingChildRetryDelay,
          self,
          createConnectionForIdentity
        )

      } else {

        val clientIdentity = context.child(childName).getOrElse(
          context.actorOf(ClientIdentity.props(), childName)
        )
        clientIdentity.forward(CreateConnection(remoteAddress))

        log.debug(s"${context.children.size} identities are connected")

      }

    case postedInboundAuthenticatedMessage@PostedInboundAuthenticatedMessage(_, AuthenticatedInboundMessage(publicKey, _)) =>

      val childName = publicKey.fingerprint
      context.child(childName).foreach(_ ! postedInboundAuthenticatedMessage)

    case TerminationRequest =>

      log.debug(s"Stopping ${sender().path.name}")
      context.stop(context.watch(sender()))

      val newStoppingChildren = stoppingChildren + sender().path.name
      log.debug(s"${newStoppingChildren.size} children are stopping")

      context.become(receive(newStoppingChildren))

    case Terminated(clientIdentity) =>

      val newStoppingChildren = stoppingChildren - sender().path.name
      log.debug(s"${newStoppingChildren.size} children are stopping")

      log.debug(s"${context.children.size} identities are connected")

      context.become(receive(newStoppingChildren))

  }

}