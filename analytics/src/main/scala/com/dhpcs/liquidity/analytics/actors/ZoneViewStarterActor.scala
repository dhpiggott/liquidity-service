package com.dhpcs.liquidity.analytics.actors

import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern.ask
import akka.persistence.query.scaladsl.{AllPersistenceIdsQuery, CurrentPersistenceIdsQuery, ReadJournal}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{KillSwitches, Materializer}
import akka.util.Timeout
import com.dhpcs.liquidity.analytics.actors.ZoneViewActor.Start
import com.dhpcs.liquidity.model.ZoneId
import com.dhpcs.liquidity.persistence.ZoneIdStringPattern

import scala.concurrent.Future
import scala.concurrent.duration._

object ZoneViewStarterActor {

  def props(readJournal: ReadJournal with CurrentPersistenceIdsQuery with AllPersistenceIdsQuery,
            zoneViewShardRegion: ActorRef)(implicit mat: Materializer): Props =
    Props(new ZoneViewStarterActor(readJournal, zoneViewShardRegion))

}

class ZoneViewStarterActor(readJournal: ReadJournal with CurrentPersistenceIdsQuery with AllPersistenceIdsQuery,
                           zoneViewShardRegion: ActorRef)(implicit mat: Materializer)
    extends Actor
    with ActorLogging {

  import context.dispatcher

  private[this] val killSwitch = {

    val currentZoneIds = readJournal
      .currentPersistenceIds()
      .collect { case ZoneIdStringPattern(uuidString) => ZoneId(UUID.fromString(uuidString)) }
      .mapAsyncUnordered(sys.runtime.availableProcessors)(zoneId => startZoneView(zoneId).map(_ => zoneId))
      .runFold(Set.empty[ZoneId])(_ + _)

    currentZoneIds.foreach(currentZones => log.info(s"Completed initialisation of ${currentZones.size} zone views"))

    val (killSwitch, done) = Source
      .fromFuture(currentZoneIds)
      .viaMat(KillSwitches.single)(Keep.right)
      .flatMapConcat(
        currentZoneIds =>
          readJournal
            .allPersistenceIds()
            .collect { case ZoneIdStringPattern(uuidString) => ZoneId(UUID.fromString(uuidString)) }
            .filterNot(currentZoneIds.contains)
            .mapAsyncUnordered(sys.runtime.availableProcessors)(startZoneView))
      .toMat(Sink.ignore)(Keep.both)
      .run()

    done.onFailure {
      case t =>
        Console.err.println(s"Exiting due to stream failure:\n$t")
        // TODO: Delegate escalation
        sys.exit(1)
    }

    killSwitch
  }

  private[this] implicit val zoneViewInitialisationTimeout = Timeout(30.seconds)

  private[this] def startZoneView(zoneId: ZoneId): Future[Unit] =
    (zoneViewShardRegion ? Start(zoneId)).mapTo[Unit]

  override def postStop(): Unit = {
    killSwitch.shutdown()
    super.postStop()
  }

  override def receive: Receive = Actor.emptyBehavior

}
