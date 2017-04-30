package com.dhpcs.liquidity.server

import java.util.UUID

import akka.Done
import akka.actor.{Actor, ActorPath, ActorSystem, Props}
import akka.pattern.ask
import akka.persistence.PersistentActor
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.Timeout
import com.dhpcs.liquidity.model.{ValueFormat, ZoneId}
import com.dhpcs.liquidity.persistence._
import com.dhpcs.liquidity.serialization.PlayJsonSerializer
import com.dhpcs.liquidity.serialization.PlayJsonSerializer._
import com.typesafe.config.ConfigFactory
import play.api.libs.json.{Json, Format => JsFormat}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

object JournalMigration {

  class EventSerializer extends PlayJsonSerializer {

    implicit final val ActorPathFormat: JsFormat[ActorPath] =
      ValueFormat[ActorPath, String](ActorPath.fromString, _.toSerializationFormat)

    implicit final val ZoneCreatedEventFormat: JsFormat[ZoneCreatedEvent]         = Json.format[ZoneCreatedEvent]
    implicit final val ZoneJoinedEventFormat: JsFormat[ZoneJoinedEvent]           = Json.format[ZoneJoinedEvent]
    implicit final val ZoneQuitEventFormat: JsFormat[ZoneQuitEvent]               = Json.format[ZoneQuitEvent]
    implicit final val ZoneNameChangedEventFormat: JsFormat[ZoneNameChangedEvent] = Json.format[ZoneNameChangedEvent]
    implicit final val MemberCreatedEventFormat: JsFormat[MemberCreatedEvent]     = Json.format[MemberCreatedEvent]
    implicit final val MemberUpdatedEventFormat: JsFormat[MemberUpdatedEvent]     = Json.format[MemberUpdatedEvent]
    implicit final val AccountCreatedEventFormat: JsFormat[AccountCreatedEvent]   = Json.format[AccountCreatedEvent]
    implicit final val AccountUpdatedEventFormat: JsFormat[AccountUpdatedEvent]   = Json.format[AccountUpdatedEvent]
    implicit final val TransactionAddedEventFormat: JsFormat[TransactionAddedEvent] =
      Json.format[TransactionAddedEvent]

    override def identifier: Int = 262953465

    override protected val formats: Map[String, Format[_ <: AnyRef]] = Map(
      manifestToFormat[ZoneCreatedEvent],
      manifestToFormat[ZoneJoinedEvent],
      manifestToFormat[ZoneQuitEvent],
      manifestToFormat[ZoneNameChangedEvent],
      manifestToFormat[MemberCreatedEvent],
      manifestToFormat[MemberUpdatedEvent],
      manifestToFormat[AccountCreatedEvent],
      manifestToFormat[AccountUpdatedEvent],
      manifestToFormat[TransactionAddedEvent]
    )

  }

  private object WriterActor {
    def props(persistenceId: String): Props = Props(new WriterActor(persistenceId))
  }

  private class WriterActor(override val persistenceId: String) extends PersistentActor {

    override val journalPluginId = "cassandra-write-journal-v2"

    override def receiveRecover: Receive =
      Actor.emptyBehavior

    override def receiveCommand: Receive = {
      case event => persist(event)(_ => sender().!(Done))
    }
  }

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem(
      "liquidity",
      ConfigFactory
        .parseString(s"""
            |akka {
            |  actor {
            |    serializers {
            |      persistence-event = "com.dhpcs.liquidity.server.JournalMigration$$EventSerializer"
            |    }
            |    serialization-bindings {
            |      "com.dhpcs.liquidity.persistence.ZoneEvent" = persistence-event
            |      "com.trueaccord.scalapb.GeneratedMessage" = proto
            |    }
            |  }
            |  persistence.journal.plugin = "cassandra-journal"
            |}
            |cassandra-journal {
            |  contact-points = ["cassandra"]
            |}
            |cassandra-journal-v1 = $${cassandra-journal}
            |cassandra-journal-v1 {
            |  keyspace = "liquidity_server"
            |}
            |cassandra-journal-v2 = $${cassandra-journal}
            |cassandra-journal-v2 {
            |  keyspace = "liquidity_server_v2"
            |}
            |cassandra-read-journal-v1 = $${cassandra-query-journal}
            |cassandra-read-journal-v1 {
            |  write-plugin = cassandra-journal-v1
            |}
            |cassandra-write-journal-v2 = $${cassandra-journal-v2}
            |cassandra-write-journal-v2 {
            |  event-adapters {
            |    zone-event-write-adapter = "com.dhpcs.liquidity.persistence.ZoneWriteEventAdapter"
            |    zone-event-read-adapter = "com.dhpcs.liquidity.persistence.ZoneReadEventAdapter"
            |  }
            |  event-adapter-bindings {
            |    "com.dhpcs.liquidity.persistence.ZoneEvent" = [zone-event-write-adapter]
            |    "com.dhpcs.liquidity.proto.persistence.ZoneEvent" = [zone-event-read-adapter]
            |  }
            |}
          """.stripMargin)
        .withFallback(ConfigFactory.defaultReference())
        .resolve()
    )
    implicit val mat = ActorMaterializer()
    implicit val ec  = ExecutionContext.global
    try Await.result(for ((eventCount, zoneCount) <- migrateZoneEvents())
                       yield println(s"Wrote $eventCount events for $zoneCount zones"),
                     Duration.Inf)
    finally Await.result(
      system.terminate(),
      Duration.Inf
    )
  }

  private def migrateZoneEvents()(implicit system: ActorSystem, mat: Materializer): Future[(Int, Int)] = {
    val readJournal = PersistenceQuery(system)
      .readJournalFor[CassandraReadJournal]("cassandra-read-journal-v1")
    readJournal
      .currentPersistenceIds()
      .collect {
        case ZoneIdStringPattern(uuidString) => ZoneId(UUID.fromString(uuidString))
      }
      .mapAsyncUnordered(sys.runtime.availableProcessors) { zoneId =>
        implicit val timeout = Timeout(5.seconds)
        import system.dispatcher
        val persistenceId = zoneId.persistenceId
        val writerActor   = system.actorOf(WriterActor.props(persistenceId))
        val result =
          readJournal
            .currentEventsByPersistenceId(persistenceId, 0, Long.MaxValue)
            .mapAsync(1)(envelope =>
              (writerActor ? envelope.event)
                .mapTo[Done]
                .map(_ => 1))
            .runFold(0)(_ + _)
            .map { eventCount =>
              println(s"Rewrote $eventCount events for $persistenceId")
              eventCount
            }
        result.onComplete(_ => system.stop(writerActor))
        result
      }
      .runFold((0, 0)) {
        case ((eventCount, zoneCount), zoneEventCount) =>
          (eventCount + zoneEventCount, zoneCount + 1)
      }
  }
}
