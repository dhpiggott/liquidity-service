package actors

import akka.actor.{Actor, ActorSystem, Props}
import akka.persistence.PersistentActor
import akka.testkit.TestProbe

import scala.concurrent.duration._

/**
  * The members of this singleton are based on https://github.com/akka/akka-persistence-cassandra/blob/v0.11/src/test/
  * scala/akka/persistence/cassandra/CassandraLifecycle.scala.
  *
  * They're copied and tweaked here because akka-cassandra-persistence does not publish the test artifact -- otherwise
  * we'd depend on the test artifact and use it directly.
  */
object AwaitPersistenceInit {
  def waitForPersistenceInitialisation(system: ActorSystem): Unit = {
    val probe = TestProbe()(system)
    system.actorOf(Props[AwaitPersistenceInitActor]).tell("hello", probe.ref)
    probe.expectMsg(35.seconds, "hello")
  }

  private[this] class AwaitPersistenceInitActor extends PersistentActor {
    override def persistenceId: String = "persistenceInit"

    override def receiveRecover: Receive = Actor.emptyBehavior

    override def receiveCommand: Receive = {
      case msg =>
        persist(msg) { _ =>
          sender() ! msg
          context.stop(self)
        }
    }
  }

}
