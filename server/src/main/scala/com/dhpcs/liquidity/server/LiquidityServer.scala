package com.dhpcs.liquidity.server

import java.net.InetAddress
import java.time.Instant

import akka.actor.{ActorSystem, CoordinatedShutdown, ExtendedActorSystem, Scheduler}
import akka.cluster.Cluster
import akka.cluster.http.management.ClusterHttpManagement
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.server.Directives.logRequestResult
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.stream.scaladsl.{Flow, Source}
import akka.stream.{ActorMaterializer, Materializer}
import akka.typed.scaladsl.AskPattern._
import akka.typed.scaladsl.adapter._
import akka.typed.{ActorRef, Props}
import akka.util.Timeout
import akka.{Done, NotUsed, typed}
import com.dhpcs.liquidity.actor.protocol.clientmonitor._
import com.dhpcs.liquidity.actor.protocol.zonemonitor._
import com.dhpcs.liquidity.actor.protocol.zonevalidator._
import com.dhpcs.liquidity.model.ProtoBindings._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.persistence.zone.ZoneEventEnvelope
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.server.LiquidityServer._
import com.dhpcs.liquidity.server.actor.ZoneAnalyticsActor.StopZoneAnalytics
import com.dhpcs.liquidity.server.actor.ZoneAnalyticsStarterActor.StopZoneAnalyticsStarter
import com.dhpcs.liquidity.server.actor._
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object LiquidityServer {

  private final val ZoneHostRole    = "zone-host"
  private final val ClientRelayRole = "client-relay"
  private final val AnalyticsRole   = "analytics"

  def main(args: Array[String]): Unit = {
    val config                        = ConfigFactory.load
    implicit val system: ActorSystem  = ActorSystem("liquidity")
    implicit val mat: Materializer    = ActorMaterializer()
    implicit val ec: ExecutionContext = ExecutionContext.global
    val clusterHttpManagement         = ClusterHttpManagement(akka.cluster.Cluster(system))
    clusterHttpManagement.start()
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseClusterExitingDone, "clusterHttpManagementStop")(() =>
      clusterHttpManagement.stop())
    val server = new LiquidityServer(
      pingInterval = FiniteDuration(config.getDuration("liquidity.server.ping-interval", SECONDS), SECONDS),
      httpInterface = config.getString("liquidity.server.http.interface"),
      httpPort = config.getInt("liquidity.server.http.port"),
      analyticsKeyspace = config.getString("liquidity.analytics.cassandra.keyspace")
    )
    val httpBinding = server.bindHttp()
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseServiceUnbind, "liquidityServerUnbind")(() =>
      for (_ <- httpBinding.flatMap(_.unbind())) yield Done)
  }
}

class LiquidityServer(pingInterval: FiniteDuration, httpInterface: String, httpPort: Int, analyticsKeyspace: String)(
    implicit system: ActorSystem,
    mat: Materializer)
    extends HttpController {

  private[this] val readJournal =
    PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

  private[this] implicit val scheduler: Scheduler = system.scheduler
  private[this] implicit val ec: ExecutionContext = system.dispatcher
  private[this] implicit val askTimeout: Timeout  = Timeout(30.seconds)

  private[this] val zoneValidatorShardRegion = typed.cluster.sharding
    .ClusterSharding(system.toTyped)
    .spawn(
      behavior = ZoneValidatorActor.shardingBehaviour,
      entityProps = Props.empty,
      typeKey = ZoneValidatorActor.ShardingTypeName,
      settings = typed.cluster.sharding.ClusterShardingSettings(system.toTyped).withRole(ZoneHostRole),
      messageExtractor = ZoneValidatorActor.messageExtractor,
      handOffStopMessage = StopZone
    )

  private[this] val clientMonitorActor = system.spawn(ClientMonitorActor.behavior, "client-monitor")
  private[this] val zoneMonitorActor   = system.spawn(ZoneMonitorActor.behavior, "zone-monitor")

  private[this] val futureAnalyticsStore =
    readJournal.session.underlying().flatMap(CassandraAnalyticsStore(analyticsKeyspace)(_, ec))

  private[this] val streamFailureHandler = PartialFunction[Throwable, Unit] { t =>
    Console.err.println("Exiting due to stream failure")
    t.printStackTrace(Console.err)
    System.exit(1)
  }

  private[this] val zoneAnalyticsShardRegion = typed.cluster.sharding
    .ClusterSharding(system.toTyped)
    .spawn(
      behavior = ZoneAnalyticsActor.shardingBehavior(readJournal, futureAnalyticsStore, streamFailureHandler),
      entityProps = Props.empty,
      typeKey = ZoneAnalyticsActor.ShardingTypeName,
      settings = typed.cluster.sharding.ClusterShardingSettings(system.toTyped).withRole(AnalyticsRole),
      messageExtractor = ZoneAnalyticsActor.messageExtractor,
      handOffStopMessage = StopZoneAnalytics
    )

  typed.cluster
    .ClusterSingleton(system.toTyped)
    .spawn(
      behavior =
        ZoneAnalyticsStarterActor.singletonBehavior(readJournal, zoneAnalyticsShardRegion, streamFailureHandler),
      singletonName = "zone-analytics-starter-singleton",
      props = Props.empty,
      settings = typed.cluster.ClusterSingletonSettings(system.toTyped).withRole(AnalyticsRole),
      terminationMessage = StopZoneAnalyticsStarter
    )

  def bindHttp(): Future[Http.ServerBinding] = Http().bindAndHandle(
    logRequestResult(("HTTP API", Logging.InfoLevel))(
      httpRoutes(enableClientRelay = Cluster(system).selfMember.roles.contains(ClientRelayRole))),
    httpInterface,
    httpPort
  )

  override protected[this] def events(persistenceId: String,
                                      fromSequenceNr: Long,
                                      toSequenceNr: Long): Source[HttpController.EventEnvelope, NotUsed] =
    readJournal
      .eventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr)
      .map {
        case EventEnvelope(_, _, sequenceNr, event) =>
          val protoEvent = event match {
            case zoneEventEnvelope: ZoneEventEnvelope =>
              ProtoBinding[ZoneEventEnvelope, proto.persistence.zone.ZoneEventEnvelope, ExtendedActorSystem]
                .asProto(zoneEventEnvelope)
          }
          HttpController.EventEnvelope(sequenceNr, protoEvent)
      }

  override protected[this] def zoneState(zoneId: ZoneId): Future[proto.model.ZoneState] = {
    val zoneState: Future[ZoneState] = zoneValidatorShardRegion ? (GetZoneStateCommand(_, zoneId))
    zoneState.map(ProtoBinding[ZoneState, proto.model.ZoneState, ExtendedActorSystem].asProto)
  }

  override protected[this] def webSocketApi(remoteAddress: InetAddress): Flow[Message, Message, NotUsed] =
    ClientConnectionActor.webSocketFlow(
      behavior = ClientConnectionActor.behavior(pingInterval, zoneValidatorShardRegion, remoteAddress)
    )

  override protected[this] def getActiveClientSummaries: Future[Set[ActiveClientSummary]] =
    clientMonitorActor ? GetActiveClientSummaries

  override protected[this] def getActiveZoneSummaries: Future[Set[ActiveZoneSummary]] =
    zoneMonitorActor ? GetActiveZoneSummaries

  override protected[this] def getZone(zoneId: ZoneId): Future[Option[Zone]] =
    futureAnalyticsStore.flatMap(_.zoneStore.retrieveOpt(zoneId))

  override protected[this] def getBalances(zoneId: ZoneId): Future[Map[AccountId, BigDecimal]] =
    futureAnalyticsStore.flatMap(_.balanceStore.retrieve(zoneId))

  override protected[this] def getClients(zoneId: ZoneId): Future[Map[ActorRef[Nothing], (Instant, PublicKey)]] =
    futureAnalyticsStore.flatMap(_.clientStore.retrieve(zoneId)(ec, system.asInstanceOf[ExtendedActorSystem]))

}
