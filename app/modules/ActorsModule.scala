package modules

import actors.ZoneRegistry
import com.google.inject.AbstractModule
import play.api.libs.concurrent.AkkaGuiceSupport

class ActorsModule extends AbstractModule with AkkaGuiceSupport {

  def configure() {
    bindActor[ZoneRegistry]("zone-registry")
  }

}
