package com.dhpcs.liquidity.analytics

import akka.actor.{ActorSystem, PoisonPill}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings}
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.ActorMaterializer
import com.dhpcs.liquidity.analytics.actors.{ZoneViewActor, ZoneViewStarterActor}
import com.typesafe.config.ConfigFactory

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

object LiquidityAnalytics {

  def main(args: Array[String]): Unit = {
    val config          = ConfigFactory.load
    implicit val system = ActorSystem("liquidity")
    implicit val mat    = ActorMaterializer()
    val readJournal     = PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
    implicit val ec     = scala.concurrent.ExecutionContext.global
    val analyticsClientFuture = for {
      session         <- readJournal.session.underlying()
      analyticsClient <- CassandraAnalyticsClient(config)(session, ExecutionContext.global)
    } yield analyticsClient
    val streamFailureHandler = PartialFunction[Throwable, Unit] { t =>
      Console.err.println("Exiting due to stream failure")
      t.printStackTrace(Console.err)
      sys.exit(1)
    }
    val zoneViewShardRegion = ClusterSharding(system).start(
      typeName = ZoneViewActor.ShardName,
      entityProps = ZoneViewActor.props(readJournal, analyticsClientFuture, streamFailureHandler),
      settings = ClusterShardingSettings(system),
      extractEntityId = ZoneViewActor.extractEntityId,
      extractShardId = ZoneViewActor.extractShardId
    )
    system.actorOf(
      ClusterSingletonManager.props(
        singletonProps = ZoneViewStarterActor.props(readJournal, zoneViewShardRegion, streamFailureHandler),
        terminationMessage = PoisonPill,
        settings = ClusterSingletonManagerSettings(system).withSingletonName("zone-view-starter")
      ),
      name = "zone-view-starter-singleton"
    )
    sys.addShutdownHook {
      Await.result(system.terminate(), Duration.Inf)
    }
  }
}
