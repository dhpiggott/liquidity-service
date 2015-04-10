package actors

import actors.ClientConnection.AuthenticatedInboundMessage
import actors.ClientIdentity.{CreateConnection, PostedInboundAuthenticatedMessage}
import actors.ClientIdentityManager.TerminationRequest
import akka.actor._

object ClientIdentity {

  def props() = Props[ClientIdentity]

  case class CreateConnection(remoteAddress: String)

  case class PostedInboundAuthenticatedMessage(connectionNumber: Int,
                                               authenticatedInboundMessage: AuthenticatedInboundMessage)

}

class ClientIdentity extends Actor with ActorLogging {

  def receive = receive(0)

  def receive(clientConnectionNumber: Int): Receive = {

    case CreateConnection(remoteAddress) =>

      val childName = clientConnectionNumber.toString
      val clientConnection = context.watch(context.actorOf(ClientConnection.props(remoteAddress), childName))
      clientConnection.forward(ClientConnection.Init)

      log.debug(s"${context.children.size} connections are open")

      context.become(receive(clientConnectionNumber + 1))

    case PostedInboundAuthenticatedMessage(connectionNumber, inboundAuthenticatedMessage) =>

      val childName = connectionNumber.toString
      context.child(childName).foreach(_ ! inboundAuthenticatedMessage)

    case Terminated(clientConnection) =>

      val childrenSize = context.children.size
      if (childrenSize != 0) {
        log.debug(s"$childrenSize connections are open")
      } else {
        log.debug(s"No connections are open; requesting termination")
        context.parent ! TerminationRequest
      }

  }

}