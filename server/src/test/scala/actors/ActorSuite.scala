package actors

import akka.actor.ActorSystem
import akka.testkit.TestKit
import org.scalatest.{BeforeAndAfterAll, Suites}

class ActorSuite(system: ActorSystem) extends Suites(
  new ZonesMonitorSpec()(system),
  new ZoneValidatorSpec()(system)
) with BeforeAndAfterAll {
  def this() = this(ActorSystem("application"))

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)
}
