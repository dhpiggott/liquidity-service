package com.dhpcs.liquidity.analytics.actors

import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern.ask
import akka.persistence.query.scaladsl.{AllPersistenceIdsQuery, ReadJournal}
import akka.stream.scaladsl.{Keep, Sink}
import akka.stream.{KillSwitches, Materializer}
import akka.util.Timeout
import com.dhpcs.liquidity.analytics.actors.ZoneViewActor.Start
import com.dhpcs.liquidity.model.ZoneId
import com.dhpcs.liquidity.persistence.ZoneIdStringPattern

import scala.concurrent.Future
import scala.concurrent.duration._

object ZoneViewStarterActor {

  def props(readJournal: ReadJournal with AllPersistenceIdsQuery, zoneViewShardRegion: ActorRef)(
      implicit mat: Materializer): Props =
    Props(new ZoneViewStarterActor(readJournal, zoneViewShardRegion))

}

class ZoneViewStarterActor(readJournal: ReadJournal with AllPersistenceIdsQuery, zoneViewShardRegion: ActorRef)(
    implicit mat: Materializer)
    extends Actor
    with ActorLogging {

  import context.dispatcher

  private[this] val killSwitch = {

    implicit val askTimeout = Timeout(5.seconds)
    val (killSwitch, done) = readJournal
      .allPersistenceIds()
      .viaMat(KillSwitches.single)(Keep.right)
      .collect { case ZoneIdStringPattern(uuidString) => ZoneId(UUID.fromString(uuidString)) }
      .mapAsyncUnordered(Integer.MAX_VALUE)(startZoneView)
      .toMat(Sink.ignore)(Keep.both)
      .run()

    done.onFailure {
      case t =>
        log.error(t, "Stopping due to stream failure")
        context.stop(self)
    }

    killSwitch
  }

  override def postStop(): Unit = {
    killSwitch.shutdown()
    super.postStop()
  }

  override def receive: Receive = Actor.emptyBehavior

  private[this] def startZoneView(zoneId: ZoneId)(implicit timeout: Timeout): Future[Unit] =
    (zoneViewShardRegion ? Start(zoneId)).mapTo[Unit]

}
