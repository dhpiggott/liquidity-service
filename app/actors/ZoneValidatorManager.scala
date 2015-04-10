package actors

import actors.ZoneValidatorManager._
import akka.actor._
import models._

import scala.concurrent.duration._

object ZoneValidatorManager {

  val StoppingChildRetryDelay = 100.milliseconds

  def props() = Props(new ZoneValidatorManager())

  case object CreateValidator

  case class ValidatorCreated(zoneId: ZoneId, validator: ActorRef)

  case class GetValidator(zoneId: ZoneId)

  case class ValidatorGot(validator: ActorRef)

  case object TerminationRequest

}

class ZoneValidatorManager extends Actor with ActorLogging {

  import context.dispatcher

  def receive: Receive = receive(Set.empty)

  def receive(stoppingChildren: Set[String]): Receive = {

    case CreateValidator =>

      def freshZoneId: ZoneId = {
        val zoneId = ZoneId()
        if (context.child(zoneId.id.toString).isEmpty) {
          zoneId
        } else {
          freshZoneId
        }
      }
      val zoneId = freshZoneId

      val childName = zoneId.id.toString

      val validator = context.child(childName).getOrElse(
        context.actorOf(ZoneValidator.props(zoneId), childName)
      )
      sender ! ValidatorCreated(zoneId, validator)

      log.debug(s"${context.children.size} validators are active")

    case getValidator@GetValidator(zoneId) =>

      val childName = zoneId.id.toString
      if (stoppingChildren.contains(childName)) {

        log.debug(s"Received request for stopping validator; scheduling retry")
        context.system.scheduler.scheduleOnce(
          ZoneValidatorManager.StoppingChildRetryDelay,
          self,
          getValidator
        )

      } else {

        val validator = context.child(childName).getOrElse(
          context.actorOf(ZoneValidator.props(zoneId), childName)
        )
        sender ! ValidatorGot(validator)

        log.debug(s"${context.children.size} validators are active")

      }

    case TerminationRequest =>

      log.debug(s"Stopping ${sender().path.name}")
      context.stop(context.watch(sender()))

      val newStoppingChildren = stoppingChildren + sender().path.name
      log.debug(s"${newStoppingChildren.size} children are stopping")

      context.become(receive(newStoppingChildren))

    case Terminated(clientIdentity) =>

      val newStoppingChildren = stoppingChildren - sender().path.name
      log.debug(s"${newStoppingChildren.size} children are stopping")

      log.debug(s"${context.children.size} validators are active")

      context.become(receive(newStoppingChildren))

  }

}
