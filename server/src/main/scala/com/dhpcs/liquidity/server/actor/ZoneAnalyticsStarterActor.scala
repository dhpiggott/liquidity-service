package com.dhpcs.liquidity.server.actor

import akka.actor.Scheduler
import akka.event.Logging
import akka.persistence.query.scaladsl._
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{KillSwitches, Materializer}
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.AskPattern._
import akka.typed.scaladsl.adapter._
import akka.typed.{ActorRef, Behavior, PostStop}
import akka.util.Timeout
import com.dhpcs.liquidity.model.ZoneId
import com.dhpcs.liquidity.server.actor.ZoneAnalyticsActor.ZoneAnalyticsMessage

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object ZoneAnalyticsStarterActor {

  sealed abstract class ZoneAnalyticsStarterMessage
  case object StopZoneAnalyticsStarter extends ZoneAnalyticsStarterMessage

  def singletonBehavior(readJournal: ReadJournal with CurrentPersistenceIdsQuery with PersistenceIdsQuery,
                        zoneAnalyticsShardRegion: ActorRef[ZoneAnalyticsMessage],
                        streamFailureHandler: PartialFunction[Throwable, Unit])(
      implicit scheduler: Scheduler,
      ec: ExecutionContext,
      mat: Materializer): Behavior[ZoneAnalyticsStarterMessage] =
    Actor.deferred { context =>
      val log = Logging(context.system.toUntyped, context.self.toUntyped)
      val killSwitch = {
        val currentZoneIds = readJournal
          .currentPersistenceIds()
          .map(ZoneId.fromPersistenceId)
          .mapAsync(1)(zoneId => startZoneAnalyticsActor(zoneAnalyticsShardRegion, zoneId).map(_ => zoneId))
          .runFold(Set.empty[ZoneId])(_ + _)
        currentZoneIds.foreach(currentZones => log.info(s"Initialized ${currentZones.size} zone views"))
        val (killSwitch, done) = Source
          .fromFuture(currentZoneIds)
          .viaMat(KillSwitches.single)(Keep.right)
          .flatMapConcat(
            currentZoneIds =>
              readJournal
                .persistenceIds()
                .map(ZoneId.fromPersistenceId)
                .filterNot(currentZoneIds.contains)
                .mapAsync(1)(zoneId =>
                  startZoneAnalyticsActor(zoneAnalyticsShardRegion, zoneId).map(_ =>
                    log.info(s"Initialized zone view for ${zoneId.id}"))))
          .toMat(Sink.ignore)(Keep.both)
          .run()
        done.failed.foreach(t => streamFailureHandler.applyOrElse(t, throw _: Throwable))
        killSwitch
      }
      Actor.immutable[ZoneAnalyticsStarterMessage]((_, message) =>
        message match {
          case StopZoneAnalyticsStarter =>
            Actor.stopped
      }) onSignal {
        case (_, PostStop) =>
          killSwitch.shutdown()
          Actor.same
      }
    }

  private implicit final val AskTimeout: Timeout = Timeout(30.seconds)

  private def startZoneAnalyticsActor(zoneAnalyticsShardRegion: ActorRef[ZoneAnalyticsMessage], zoneId: ZoneId)(
      implicit scheduler: Scheduler): Future[Unit] =
    zoneAnalyticsShardRegion ? (ZoneAnalyticsActor.StartZoneAnalytics(_, zoneId))

}
