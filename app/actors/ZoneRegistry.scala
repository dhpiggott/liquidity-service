package actors

import actors.ZoneRegistry._
import akka.actor._
import com.dhpcs.liquidity.models.ZoneId

import scala.concurrent.duration._

object ZoneRegistry {

  val StoppingChildRetryDelay = 100.milliseconds

  def props() = Props(new ZoneRegistry)

  case object CreateValidator

  case class ValidatorCreated(zoneId: ZoneId, validator: ActorRef)

  case class GetValidator(zoneId: ZoneId)

  case class ValidatorGot(validator: ActorRef)

  case object TerminationRequest

}

class ZoneRegistry extends Actor with ActorLogging {

  import context.dispatcher

  override def receive = receive(Set.empty)

  def receive(stoppingChildren: Set[String]): Receive = {

    case CreateValidator =>

      def freshZoneId: ZoneId = {
        val zoneId = ZoneId.generate
        if (context.child(zoneId.id.toString).isEmpty) {
          zoneId
        } else {
          freshZoneId
        }
      }
      val zoneId = freshZoneId

      val childName = zoneId.id.toString

      val validator = context.actorOf(ZoneValidator.props(zoneId), childName)
      sender ! ValidatorCreated(zoneId, validator)

      log.debug(s"${context.children.size} validators are active")

    case getValidator@GetValidator(zoneId) =>

      val childName = zoneId.id.toString
      if (stoppingChildren.contains(childName)) {

        log.debug(s"Received request for stopping validator; scheduling retry")
        context.system.scheduler.scheduleOnce(
          ZoneRegistry.StoppingChildRetryDelay,
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
      log.debug(s"${newStoppingChildren.size} validators are stopping")

      context.become(receive(newStoppingChildren))

    case Terminated(validator) =>

      val newStoppingChildren = stoppingChildren - validator.path.name
      log.debug(s"${newStoppingChildren.size} validators are stopping")

      log.debug(s"${context.children.size} validators are active")

      context.become(receive(newStoppingChildren))

  }

}
