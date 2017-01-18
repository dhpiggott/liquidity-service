package com.dhpcs.liquidity.analytics

import akka.actor.{ActorSystem, PoisonPill}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings}
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.ActorMaterializer
import com.dhpcs.liquidity.analytics.actors.{ZoneAnalyticsActor, ZoneAnalyticsStarterActor}
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

object LiquidityAnalytics {

  def main(args: Array[String]): Unit = {
    val config               = ConfigFactory.load
    implicit val system      = ActorSystem("liquidity")
    implicit val mat         = ActorMaterializer()
    val readJournal          = PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
    implicit val ec          = ExecutionContext.global
    val futureAnalyticsStore = readJournal.session.underlying().flatMap(CassandraAnalyticsStore(config)(_, ec))
    val streamFailureHandler = PartialFunction[Throwable, Unit] { t =>
      Console.err.println("Exiting due to stream failure")
      t.printStackTrace(Console.err)
      sys.exit(1)
    }
    val zoneAnalyticsShardRegion = ClusterSharding(system).start(
      typeName = ZoneAnalyticsActor.ShardTypeName,
      entityProps = ZoneAnalyticsActor.props(readJournal, futureAnalyticsStore, streamFailureHandler),
      settings = ClusterShardingSettings(system),
      extractEntityId = ZoneAnalyticsActor.extractEntityId,
      extractShardId = ZoneAnalyticsActor.extractShardId
    )
    system.actorOf(
      ClusterSingletonManager.props(
        singletonProps = ZoneAnalyticsStarterActor.props(readJournal, zoneAnalyticsShardRegion, streamFailureHandler),
        terminationMessage = PoisonPill,
        settings = ClusterSingletonManagerSettings(system).withSingletonName("zone-analytics-starter")
      ),
      name = "zone-analytics-starter-singleton"
    )
    sys.addShutdownHook {
      Await.result(system.terminate(), Duration.Inf)
    }
  }
}
