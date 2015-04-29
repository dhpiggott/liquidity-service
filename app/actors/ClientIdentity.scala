package actors

import actors.ClientConnection.AuthenticatedCommand
import actors.ClientIdentity.{CreateConnection, PostedAuthenticatedCommand}
import actors.ClientIdentityManager.TerminationRequest
import akka.actor._

object ClientIdentity {

  def props() = Props[ClientIdentity]

  case class CreateConnection(remoteAddress: String)

  case class PostedAuthenticatedCommand(connectionNumber: Int,
                                        authenticatedCommand: AuthenticatedCommand)

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

    case PostedAuthenticatedCommand(connectionNumber, authenticatedCommand) =>

      val childName = connectionNumber.toString
      context.child(childName).foreach(_ ! authenticatedCommand)

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