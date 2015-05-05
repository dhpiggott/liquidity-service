package actors

import play.api._
import play.api.libs.concurrent.Akka

object Actors {

  private def actors(implicit app: Application) = app.plugin[Actors]
    .getOrElse(sys.error("Actors plugin not registered"))

  def zoneRegistry(implicit app: Application) = actors.zoneRegistry

}

class Actors(application: Application) extends Plugin {

  private def system = Akka.system(application)

  private lazy val zoneRegistry = system.actorOf(ZoneRegistry.props(), "zoneRegistry")

}