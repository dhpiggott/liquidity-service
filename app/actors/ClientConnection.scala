package actors

import actors.ClientConnection._
import actors.ZoneValidatorManager.{CreateValidator, GetValidator, ValidatorCreated, ValidatorGot}
import akka.actor._
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import com.dhpcs.liquidity.models._
import org.joda.time.DateTime
import play.api.Play.current
import play.api.libs.iteratee.Concurrent.Channel
import play.api.libs.iteratee.{Concurrent, Enumerator}

import scala.concurrent.duration._

object ClientConnection {

  implicit val GetValidatorTimeout = Timeout(ZoneValidatorManager.StoppingChildRetryDelay * 10)

  def props(remoteAddress: String) = Props(new ClientConnection(remoteAddress))

  case object Init

  case class AuthenticatedCommand(publicKey: PublicKey, command: Command)

  private case class Connected(channel: Concurrent.Channel[Event])

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

    def props(channel: Channel[Event]) = Props(new ChannelHolder(channel))

  }

  class ChannelHolder(channel: Channel[Event]) extends Actor with ActorLogging {

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

      case outboundMessage: Event =>

        channel.push(outboundMessage)

    }

  }

}

class ClientConnection(remoteAddress: String) extends Actor with ActorLogging {

  import context.dispatcher

  var joinedValidators = Map.empty[ZoneId, ActorRef]

  def receive = waiting

  def waiting: Receive = {

    case Init =>

      val enumerator = Enumerator[Event](
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
          context.actorOf(ClientConnection.ChannelHolder.props(channel), "channelHolder")
        )
      )

  }

  def connected(channelHolder: ActorRef): Receive = {

    case ClientConnection.Pulse.PushHeartbeat =>

      channelHolder ! Heartbeat(new DateTime)

    case event: Event =>

      channelHolder.forward(event)

    case authenticatedCommand: AuthenticatedCommand =>

      authenticatedCommand.command match {

        case CreateZone(_, _) =>

          (Actors.zoneValidatorManager ? CreateValidator)
            .mapTo[ValidatorCreated]
            .map { case ValidatorCreated(zoneId, validator) =>
            validator ! authenticatedCommand
            CacheValidator(zoneId, validator)
          }.pipeTo(self)

        case JoinZone(zoneId) =>

          (Actors.zoneValidatorManager ? GetValidator(zoneId))
            .mapTo[ValidatorGot]
            .map { case ValidatorGot(validator) =>
            validator ! authenticatedCommand
            CacheValidator(zoneId, validator)
          }.pipeTo(self)

        case QuitZone(zoneId) =>

          joinedValidators.get(zoneId).foreach { validator =>
            validator ! authenticatedCommand
            context.unwatch(validator)

            joinedValidators -= zoneId
          }

        case zoneCommand: ZoneCommand =>

          joinedValidators.get(zoneCommand.zoneId).foreach(_ ! authenticatedCommand)

      }

    case CacheValidator(zoneId, validator) =>

      context.watch(validator)

      joinedValidators += (zoneId -> validator)

    case Terminated(validator) =>

      joinedValidators = joinedValidators.filterNot { case (zoneId, v) =>
        val remove = v == validator
        if (remove) {
          channelHolder ! ZoneTerminated(zoneId)
        }
        remove
      }

    case Disconnected(reason) =>

      log.debug(s"$remoteAddress disconnected ($reason)")

      context.stop(self)

  }

}