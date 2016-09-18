package com.dhpcs.liquidity.models

import java.util.UUID

import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.ask
import akka.persistence.PersistentActor
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

object JournalRewriter {
  private final val ZoneIdStringPattern =
    """ZoneId\(([0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\)""".r

  private object RewriterActor {
    def props(persistenceId: String): Props = Props(new RewriterActor(persistenceId))
  }

  private class RewriterActor(override val persistenceId: String) extends PersistentActor {

    override val journalPluginId = "cassandra-rewrite-journal"

    override def receiveRecover: Receive =
      Actor.emptyBehavior

    override def receiveCommand: Receive = {
      case event => persist(event)(_ =>
        sender().!(())
      )
    }
  }

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem(
      "liquidity",
      ConfigFactory.parseString(
        """
          |akka {
          |  actor {
          |    serializers.event = "com.dhpcs.liquidity.model.PlayJsonEventSerializer"
          |    serialization-bindings {
          |      "java.io.Serializable" = none
          |      "com.dhpcs.liquidity.model.Event" = event
          |    }
          |  }
          |  persistence.journal.plugin = "cassandra-read-journal"
          |}
          |cassandra-journal {
          |  contact-points = ["cassandra"]
          |  event-adapters.legacy-event = "com.dhpcs.liquidity.models.LegacyReadEventAdapter"
          |  event-adapter-bindings {
          |    "actors.ZoneValidator$Event" = legacy-event
          |  }
          |}
          |cassandra-read-journal = ${cassandra-journal}
          |cassandra-rewrite-journal = ${cassandra-journal}
          |cassandra-rewrite-journal.keyspace = "akka_rewrite"
        """.stripMargin).withFallback(ConfigFactory.defaultReference()).resolve()
    )
    implicit val mat = ActorMaterializer()
    implicit val ec = ExecutionContext.global
    try
      Await.result(
        for {
          (eventCount, zoneCount) <- rewriteZoneEvents()
        } yield {
          println(s"Rewrote $eventCount events for $zoneCount zones")
        },
        Duration.Inf)
    finally
      Await.result(
        system.terminate(),
        Duration.Inf
      )
  }

  private def rewriteZoneEvents()(implicit system: ActorSystem,
                                  mat: Materializer): Future[(Int, Int)] = {
    val readJournal = PersistenceQuery(system)
      .readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
    readJournal
      .currentPersistenceIds()
      .collect { case ZoneIdStringPattern(uuidString) =>
        ZoneId(UUID.fromString(uuidString))
      }
      .mapAsyncUnordered(sys.runtime.availableProcessors) { zoneId =>
        implicit val timeout = Timeout(5.seconds)
        import system.dispatcher
        val persistenceId = zoneId.toString
        val rewriterActor = system.actorOf(RewriterActor.props(persistenceId))
        val result = readJournal
          .currentEventsByPersistenceId(persistenceId, 0, Long.MaxValue)
          .mapAsync(1)(envelope =>
            (rewriterActor ? envelope.event)
              .mapTo[Unit]
              .map(_ => 1)
          )
          .runFold(0)(_ + _)
          .map { eventCount =>
            println(s"Rewrote $eventCount events for $persistenceId")
            eventCount
          }
        result.onComplete(_ => system.stop(rewriterActor))
        result
      }
      .runFold((0, 0)) {
        case ((eventCount, zoneCount), zoneEventCount) =>
          (eventCount + zoneEventCount, zoneCount + 1)
      }
  }
}
