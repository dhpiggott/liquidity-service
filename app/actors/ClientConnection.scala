package actors

import actors.ClientConnection._
import actors.ZoneValidatorManager.{CreateValidator, GetValidator, ValidatorCreated, ValidatorGot}
import akka.actor._
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import controllers.Application.PublicKey
import models._
import org.joda.time.DateTime
import play.api.Play.current
import play.api.libs.iteratee.Concurrent.Channel
import play.api.libs.iteratee.{Concurrent, Enumerator}

import scala.concurrent.duration._

object ClientConnection {

  implicit val GetValidatorTimeout = Timeout(ZoneValidatorManager.StoppingChildRetryDelay * 10)

  def props(remoteAddress: String) = Props(new ClientConnection(remoteAddress))

  case object Init

  case class AuthenticatedInboundMessage(publicKey: PublicKey, inboundMessage: InboundMessage)

  private case class Connected(channel: Concurrent.Channel[OutboundMessage])

  private case class CacheValidator(zoneId: ZoneId, validator: ActorRef)

  private case class Disconnected(reason: String)

  object Pulse {

    val HeartbeatInterval = 5.seconds

    def props() = Props(new Pulse())

    case object PushHeartbeat

  }

  class Pulse extends Actor with ActorLogging {

    import context.dispatcher

    val heartbeatTask = context.system.scheduler.schedule(
      Pulse.HeartbeatInterval,
      Pulse.HeartbeatInterval,
      context.parent,
      ClientConnection.Pulse.PushHeartbeat
    )

    override def postStop(): Unit = {

      heartbeatTask.cancel()
      log.debug("Stopped heartbeat task")

    }

    def receive: Receive = Actor.emptyBehavior

  }

  object ChannelHolder {

    def props(channel: Channel[OutboundMessage]) = Props(new ChannelHolder(channel))

  }

  class ChannelHolder(channel: Channel[OutboundMessage]) extends Actor with ActorLogging {

    override def postStop(): Unit = {

      /*
       * Normally the client is the one who disconnects and in such cases there's no need to push EOF.
       *
       * If at a later point the ability to "kick" clients is added, this will ensure they actually disconnect.
       *
       * Perhaps more importantly, if the actor does fail for any reason, the client at the other end will get
       * disconnected and so will know that it needs to reconnect.
       */
      channel.eofAndEnd()

      log.debug("Pushed EOF and ended channel")

    }

    def receive = {

      case ClientConnection.Pulse.PushHeartbeat =>

        channel.push(Heartbeat(new DateTime))

      case outboundMessage: OutboundMessage =>

        channel.push(outboundMessage)

    }

  }

}

class ClientConnection(remoteAddress: String) extends Actor with ActorLogging {

  import context.dispatcher

  def receive = waiting

  def waiting: Receive = {

    case Init =>

      val enumerator = Enumerator[OutboundMessage](
        ConnectionNumber(self.path.name.toInt)
      ).andThen(
          Concurrent.unicast(
            channel => {
              self ! Connected(channel)
            },
            onComplete = {
              self ! Disconnected("stream complete")
            },
            onError = (error, input) => {
              self ! Disconnected(s"stream error: $error")
            }
          )
        )

      sender ! enumerator

      context.become(connecting)

  }

  def connecting: Receive = {

    case Connected(channel) =>

      log.debug(s"$remoteAddress connected")

      context.actorOf(ClientConnection.Pulse.props(), "pulse")

      context.become(
        connected(
          context.actorOf(ClientConnection.ChannelHolder.props(channel), "channelHolder"),
          Map.empty
        )
      )

  }

  def connected(channelHolder: ActorRef, validators: Map[ZoneId, ActorRef]): Receive = {

    case ClientConnection.Pulse.PushHeartbeat =>

      channelHolder.forward(Heartbeat(new DateTime))

    case outboundMessage: OutboundMessage =>

      channelHolder.forward(outboundMessage)

    case authenticatedInboundMessage: AuthenticatedInboundMessage =>

      authenticatedInboundMessage.inboundMessage match {

        case CreateZone(_, _) =>

          (Actors.zoneValidatorManager ? CreateValidator)
            .mapTo[ValidatorCreated]
            .map { case ValidatorCreated(zoneId, validator) =>
            validator ! authenticatedInboundMessage
            CacheValidator(zoneId, validator)
          }.pipeTo(self)

        case JoinZone(zoneId) =>

          (Actors.zoneValidatorManager ? GetValidator(zoneId))
            .mapTo[ValidatorGot]
            .map { case ValidatorGot(validator) =>
            validator ! authenticatedInboundMessage
            CacheValidator(zoneId, validator)
          }.pipeTo(self)

        case QuitZone(zoneId) =>

          validators.get(zoneId).foreach { validator =>
            validator ! authenticatedInboundMessage
            context.unwatch(validator)

            context.become(connected(channelHolder, validators - zoneId))
          }

        case inboundZoneMessage: InboundZoneMessage =>

          validators.get(inboundZoneMessage.zoneId).foreach(_ ! authenticatedInboundMessage)

      }

    case CacheValidator(zoneId, validator) =>

      context.watch(validator)

      context.become(connected(channelHolder, validators + (zoneId -> validator)))

    case Terminated(validator) =>

      // TODO: Tell the client the validator was destroyed or auto-rejoin on its behalf.
      // TODO: Revert to a field for consistency with zone validator?
      context.become(connected(channelHolder, validators.filterNot { case (_, v) => v == validator }))

    case Disconnected(reason) =>

      log.debug(s"$remoteAddress disconnected ($reason)")

      context.stop(self)

  }

}