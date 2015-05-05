package actors

import actors.ClientConnection._
import actors.ZoneRegistry.{CreateValidator, GetValidator, ValidatorCreated, ValidatorGot}
import akka.actor._
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import com.dhpcs.liquidity.models._

object ClientConnection {

  implicit val GetValidatorTimeout = Timeout(ZoneRegistry.StoppingChildRetryDelay * 10)

  def props(publicKey: PublicKey, zoneRegistry: ActorRef)(upstream: ActorRef) =
    Props(new ClientConnection(publicKey, upstream, zoneRegistry))

  case class AuthenticatedCommand(publicKey: PublicKey, command: Command)

  private case class CacheValidator(zoneId: ZoneId, validator: ActorRef)

}

class ClientConnection(publicKey: PublicKey,
                       zoneRegistry: ActorRef,
                       upstream: ActorRef) extends Actor with ActorLogging {

  import context.dispatcher

  var joinedValidators = Map.empty[ZoneId, ActorRef]

  override def postStop() {
    log.debug(s"Stopped actor for ${publicKey.fingerprint}")
  }

  override def preStart() {
    log.debug(s"Started actor for ${publicKey.fingerprint}")
  }

  def receive = {

    case command: CreateZone =>

      log.debug(s"Received $command}")

      (zoneRegistry ? CreateValidator)
        .mapTo[ValidatorCreated]
        .map { case ValidatorCreated(zoneId, validator) =>
        validator ! AuthenticatedCommand(publicKey, command)
        CacheValidator(zoneId, validator)
      }.pipeTo(self)

    case command@JoinZone(zoneId) =>

      log.debug(s"Received $command}")

      (zoneRegistry ? GetValidator(zoneId))
        .mapTo[ValidatorGot]
        .map { case ValidatorGot(validator) =>
        validator ! AuthenticatedCommand(publicKey, command)
        CacheValidator(zoneId, validator)
      }.pipeTo(self)

    case command@QuitZone(zoneId) =>

      log.debug(s"Received $command}")

      joinedValidators.get(zoneId).foreach { validator =>
        validator ! AuthenticatedCommand(publicKey, command)
        context.unwatch(validator)

        joinedValidators -= zoneId
      }

    case zoneCommand: ZoneCommand =>

      log.debug(s"Received $zoneCommand}")

      joinedValidators.get(zoneCommand.zoneId).foreach(_ ! AuthenticatedCommand(publicKey, zoneCommand))

    case CacheValidator(zoneId, validator) =>

      context.watch(validator)

      joinedValidators += (zoneId -> validator)

    case event: Event =>

      log.debug(s"Received $event}")

      upstream ! event

    case Terminated(validator) =>

      joinedValidators = joinedValidators.filterNot { case (zoneId, v) =>
        val remove = v == validator
        if (remove) {
          upstream ! ZoneTerminated(zoneId)
        }
        remove
      }

  }

}