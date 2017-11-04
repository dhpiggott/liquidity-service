package com.dhpcs.liquidity.server

import java.time.Instant

import akka.Done
import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.ask
import akka.persistence.PersistentActor
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{EventEnvelope, Offset, PersistenceQuery, TimeBasedUUID}
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.Timeout
import com.datastax.driver.core.utils.UUIDs
import com.dhpcs.liquidity.persistence.EventTags
import com.dhpcs.liquidity.persistence.zone._
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

object JournalMigration {

  private object WriterActor {
    def props(persistenceId: String): Props = Props(new WriterActor(persistenceId))
  }

  private class WriterActor(override val persistenceId: String) extends PersistentActor {

    override def journalPluginId = "jdbc-journal"

    override def receiveRecover: Receive = Actor.emptyBehavior

    override def receiveCommand: Receive = {
      case event =>
        persist(event) { _ =>
          sender() ! Done
          context.stop(self)
        }
    }
  }

  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem(
      "liquidity",
      ConfigFactory
        .parseString(s"""
            |akka.actor {
            |  serializers {
            |    zone-record = "com.dhpcs.liquidity.server.serialization.ZoneRecordSerializer"
            |  }
            |  serialization-bindings {
            |    "com.dhpcs.liquidity.persistence.zone.ZoneRecord" = zone-record
            |  }
            |  allow-java-serialization = off
            |}
            |cassandra-journal {
            |  contact-points = ["cassandra"]
            |  keyspace = "liquidity_journal_v4"
            |}
            |jdbc-journal {
            |  slick {
            |    profile = "slick.jdbc.MySQLProfile$$"
            |    db {
            |      driver = "com.mysql.jdbc.Driver"
            |      url = "jdbc:mysql://mysql/liquidity_journal?cachePrepStmts=true&cacheCallableStmts=true&cacheServerConfiguration=true&useLocalSessionState=true&elideSetAutoCommits=true&alwaysSendSetIsolation=false&enableQueryTimeouts=false&connectionAttributes=none&verifyServerCertificate=false&useSSL=false&useUnicode=true&useLegacyDatetimeCode=false&serverTimezone=UTC&rewriteBatchedStatements=true"
            |      user = "root"
            |      password = ""
            |      connectionTestQuery = "SELECT 1"
            |    }
            |  }
            |  event-adapters {
            |    zone-event = "com.dhpcs.liquidity.server.ZoneEventAdapter"
            |  }
            |  event-adapter-bindings {
            |    "com.dhpcs.liquidity.persistence.zone.ZoneEventEnvelope" = zone-event
            |  }
            |}
          """.stripMargin)
        .withFallback(ConfigFactory.defaultReference())
        .resolve()
    )
    implicit val mat: ActorMaterializer = ActorMaterializer()
    implicit val ec: ExecutionContext   = ExecutionContext.global
    try Await.result(
      for (eventCount <- migrateZoneEvents()) yield log.info(s"Copied zone $eventCount events"),
      Duration.Inf
    )
    finally Await.result(
      for (_ <- system.terminate()) yield (),
      Duration.Inf
    )
  }

  private def migrateZoneEvents()(implicit system: ActorSystem, mat: Materializer): Future[Int] =
    PersistenceQuery(system)
      .readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
      .currentEventsByTag(EventTags.ZoneEventTag, Offset.noOffset)
      .mapAsync(1) { eventEnvelope =>
        val persistenceId                 = eventEnvelope.persistenceId
        val event                         = cleanZoneEvent(eventEnvelope).event
        val offset                        = eventEnvelope.offset.asInstanceOf[TimeBasedUUID]
        val writerActor                   = system.actorOf(WriterActor.props(persistenceId))
        implicit val ec: ExecutionContext = ExecutionContext.global
        implicit val timeout: Timeout     = Timeout(5.seconds)
        for (_ <- (writerActor ? event).mapTo[Done]) yield offset
      }
      .zipWithIndex
      .groupedWithin(n = 1000, d = 30.seconds)
      .map { group =>
        val (offset, index) = group.last
        log.info(s"Copied ${group.size} zone events (total: ${index + 1}, offset: ${Instant.ofEpochMilli(
          UUIDs.unixTimestamp(offset.value))})")
        group.size
      }
      .runFold(0)(_ + _)

  private def cleanZoneEvent(eventEnvelope: EventEnvelope): EventEnvelope = {
    val zoneEventEnvelope = eventEnvelope.event.asInstanceOf[ZoneEventEnvelope]
    zoneEventEnvelope.zoneEvent match {
      case ClientJoinedEvent(Some("akka://liquidity/deadLetters")) =>
        eventEnvelope.copy(
          event = zoneEventEnvelope.copy(
            zoneEvent = ClientJoinedEvent(actorRefString = None)
          ))

      case ClientQuitEvent(Some("akka://liquidity/deadLetters")) =>
        eventEnvelope.copy(
          event = zoneEventEnvelope.copy(
            zoneEvent = ClientJoinedEvent(actorRefString = None)
          ))

      case _ =>
        eventEnvelope
    }
  }
}
