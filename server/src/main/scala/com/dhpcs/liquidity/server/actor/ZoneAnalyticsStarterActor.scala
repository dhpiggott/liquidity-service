package com.dhpcs.liquidity.server.actor

import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern.ask
import akka.persistence.query.scaladsl.{CurrentPersistenceIdsQuery, PersistenceIdsQuery, ReadJournal}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{KillSwitches, Materializer}
import akka.util.Timeout
import com.dhpcs.liquidity.model.ZoneId
import com.dhpcs.liquidity.persistence.ZoneIdStringPattern

import scala.concurrent.Future
import scala.concurrent.duration._

object ZoneAnalyticsStarterActor {
  def props(readJournal: ReadJournal with CurrentPersistenceIdsQuery with PersistenceIdsQuery,
            zoneAnalyticsShardRegion: ActorRef,
            streamFailureHandler: PartialFunction[Throwable, Unit])(implicit mat: Materializer): Props =
    Props(new ZoneAnalyticsStarterActor(readJournal, zoneAnalyticsShardRegion, streamFailureHandler, mat))
}

class ZoneAnalyticsStarterActor(readJournal: ReadJournal with CurrentPersistenceIdsQuery with PersistenceIdsQuery,
                                zoneViewShardRegion: ActorRef,
                                streamFailureHandler: PartialFunction[Throwable, Unit],
                                _mat: Materializer)
    extends Actor
    with ActorLogging {

  import context.dispatcher

  private[this] implicit val mat = _mat

  private[this] val killSwitch = {

    val currentZoneIds = readJournal
      .currentPersistenceIds()
      .collect { case ZoneIdStringPattern(uuidString) => ZoneId(UUID.fromString(uuidString)) }
      .mapAsyncUnordered(sys.runtime.availableProcessors)(zoneId => startZoneView(zoneId).map(_ => zoneId))
      .runFold(Set.empty[ZoneId])(_ + _)

    currentZoneIds.foreach(currentZones => log.info(s"Initialized ${currentZones.size} zone views"))

    val (killSwitch, done) = Source
      .fromFuture(currentZoneIds)
      .viaMat(KillSwitches.single)(Keep.right)
      .flatMapConcat(
        currentZoneIds =>
          readJournal
            .persistenceIds()
            .collect { case ZoneIdStringPattern(uuidString) => ZoneId(UUID.fromString(uuidString)) }
            .filterNot(currentZoneIds.contains)
            .mapAsyncUnordered(sys.runtime.availableProcessors)(zoneId =>
              startZoneView(zoneId).map(_ => log.info(s"Initialized zone view for ${zoneId.id}"))))
      .toMat(Sink.ignore)(Keep.both)
      .run()

    done.failed.foreach(t => streamFailureHandler.applyOrElse(t, throw _: Throwable))

    killSwitch
  }

  private[this] implicit val zoneViewInitialisationTimeout = Timeout(30.seconds)

  private[this] def startZoneView(zoneId: ZoneId): Future[Unit] =
    (zoneViewShardRegion ? ZoneAnalyticsActor.Start(zoneId)).mapTo[ZoneAnalyticsActor.Started.type].map(_ => ())

  override def postStop(): Unit = {
    killSwitch.shutdown()
    super.postStop()
  }

  override def receive: Receive = Actor.emptyBehavior

}
