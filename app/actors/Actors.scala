package actors

import play.api._
import play.api.libs.concurrent.Akka

object Actors {

  private def actors(implicit app: Application) = app.plugin[Actors]
    .getOrElse(sys.error("Actors plugin not registered"))

  def clientIdentityManager(implicit app: Application) = actors.clientIdentityManager

  def zoneValidatorManager(implicit app: Application) = actors.zoneValidatorManager

}

class Actors(application: Application) extends Plugin {

  private def system = Akka.system(application)

  private lazy val clientIdentityManager = system.actorOf(ClientIdentityManager.props(), "clientIdentityManager")

  private lazy val zoneValidatorManager = system.actorOf(ZoneValidatorManager.props(), "zoneValidatorManager")

}