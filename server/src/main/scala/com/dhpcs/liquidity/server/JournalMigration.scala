package com.dhpcs.liquidity.server

import java.util.UUID

import akka.Done
import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.ask
import akka.persistence.PersistentActor
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.Timeout
import com.dhpcs.liquidity.model.ZoneId
import com.dhpcs.liquidity.persistence._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.persistence.ZoneEvent.Event
import com.dhpcs.liquidity.proto.persistence.ZoneEvent.Event.Empty
import com.dhpcs.liquidity.serialization.ProtoConverter
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

object JournalMigration {

  private object WriterActor {
    def props(persistenceId: String): Props = Props(new WriterActor(persistenceId))
  }

  private class WriterActor(override val persistenceId: String) extends PersistentActor {

    override val journalPluginId = "cassandra-write-journal-v3"

    override def receiveRecover: Receive =
      Actor.emptyBehavior

    override def receiveCommand: Receive = {
      case zoneEvent: proto.persistence.ZoneEvent =>
        val v3ZoneEvent = zoneEvent.event match {
          case Empty => sys.error("Empty ZoneEvent")
          case Event.V2ZoneCreatedEvent(zoneCreatedEvent) =>
            ProtoConverter[ZoneCreatedEvent, proto.persistence.ZoneCreatedEvent].asScala(
              proto.persistence.ZoneCreatedEvent(
                zoneEvent.timestamp,
                zoneCreatedEvent.zone
              )
            )
          case Event.V2ZoneJoinedEvent(zoneJoinedEvent) =>
            ProtoConverter[ZoneJoinedEvent, proto.persistence.ZoneJoinedEvent].asScala(
              proto.persistence.ZoneJoinedEvent(
                zoneEvent.timestamp,
                zoneJoinedEvent.clientConnectionActorPath,
                zoneJoinedEvent.publicKey
              )
            )
          case Event.V2ZoneQuitEvent(zoneQuitEvent) =>
            ProtoConverter[ZoneQuitEvent, proto.persistence.ZoneQuitEvent].asScala(
              proto.persistence.ZoneQuitEvent(
                zoneEvent.timestamp,
                zoneQuitEvent.clientConnectionActorPath
              )
            )
          case Event.V2ZoneNameChangedEvent(zoneNameChangedEvent) =>
            ProtoConverter[ZoneNameChangedEvent, proto.persistence.ZoneNameChangedEvent].asScala(
              proto.persistence.ZoneNameChangedEvent(
                zoneEvent.timestamp,
                zoneNameChangedEvent.name
              )
            )
          case Event.V2MemberCreatedEvent(memberCreatedEvent) =>
            ProtoConverter[MemberCreatedEvent, proto.persistence.MemberCreatedEvent].asScala(
              proto.persistence.MemberCreatedEvent(
                zoneEvent.timestamp,
                memberCreatedEvent.member
              )
            )
          case Event.V2MemberUpdatedEvent(memberUpdatedEvent) =>
            ProtoConverter[MemberUpdatedEvent, proto.persistence.MemberUpdatedEvent].asScala(
              proto.persistence.MemberUpdatedEvent(
                zoneEvent.timestamp,
                memberUpdatedEvent.member
              )
            )
          case Event.V2AccountCreatedEvent(accountCreatedEvent) =>
            ProtoConverter[AccountCreatedEvent, proto.persistence.AccountCreatedEvent].asScala(
              proto.persistence.AccountCreatedEvent(
                zoneEvent.timestamp,
                accountCreatedEvent.account
              )
            )
          case Event.V2AccountUpdatedEvent(accountUpdatedEvent) =>
            ProtoConverter[AccountUpdatedEvent, proto.persistence.AccountUpdatedEvent].asScala(
              proto.persistence.AccountUpdatedEvent(
                zoneEvent.timestamp,
                accountUpdatedEvent.account
              )
            )
          case Event.V2TransactionAddedEvent(transactionAddedEvent) =>
            ProtoConverter[TransactionAddedEvent, proto.persistence.TransactionAddedEvent].asScala(
              proto.persistence.TransactionAddedEvent(
                zoneEvent.timestamp,
                transactionAddedEvent.transaction
              )
            )
        }
        persist(v3ZoneEvent)(_ => sender().!(Done))
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
            |      zone-event-v3 = "com.dhpcs.liquidity.persistence.ZoneEventSerializer"
            |    }
            |    serialization-bindings {
            |      "com.dhpcs.liquidity.persistence.ZoneEvent" = zone-event-v3
            |      "com.trueaccord.scalapb.GeneratedMessage" = proto
            |    }
            |  }
            |  persistence.journal.plugin = "cassandra-journal"
            |}
            |cassandra-journal {
            |  contact-points = ["cassandra"]
            |}
            |cassandra-journal-v2 = $${cassandra-journal}
            |cassandra-journal-v2 {
            |  keyspace = "liquidity_server_v2"
            |}
            |cassandra-journal-v3 = $${cassandra-journal}
            |cassandra-journal-v3 {
            |  keyspace = "liquidity_server_v3"
            |}
            |cassandra-read-journal-v2 = $${cassandra-query-journal}
            |cassandra-read-journal-v2 {
            |  write-plugin = cassandra-journal-v2
            |}
            |cassandra-write-journal-v3 = $${cassandra-journal-v3}
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
      .readJournalFor[CassandraReadJournal]("cassandra-read-journal-v2")
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
