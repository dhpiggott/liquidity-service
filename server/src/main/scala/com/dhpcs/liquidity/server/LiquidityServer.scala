package com.dhpcs.liquidity.server

import java.net.InetAddress
import java.util.concurrent.Executors

import akka.actor.{ActorSystem, CoordinatedShutdown, Scheduler}
import akka.cluster.Cluster
import akka.cluster.http.management.ClusterHttpManagement
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.server.Directives.logRequestResult
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.stream.scaladsl.{Flow, Source}
import akka.stream.{ActorMaterializer, Materializer}
import akka.typed.Props
import akka.typed.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.typed.cluster.{ActorRefResolver, ClusterSingleton, ClusterSingletonSettings}
import akka.typed.scaladsl.AskPattern._
import akka.typed.scaladsl.adapter._
import akka.util.Timeout
import akka.{Done, NotUsed}
import cats.effect.IO
import com.dhpcs.liquidity.actor.protocol.ProtoBindings._
import com.dhpcs.liquidity.actor.protocol.clientmonitor._
import com.dhpcs.liquidity.actor.protocol.zonemonitor._
import com.dhpcs.liquidity.actor.protocol.zonevalidator._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.persistence.zone.{ZoneEventEnvelope, ZoneState}
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.server.LiquidityServer._
import com.dhpcs.liquidity.server.actor.ZoneAnalyticsActor.StopZoneAnalytics
import com.dhpcs.liquidity.server.actor._
import com.typesafe.config.ConfigFactory
import doobie._
import doobie.hikari._
import doobie.hikari.implicits._
import doobie.implicits._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object LiquidityServer {

  private final val ZoneHostRole    = "zone-host"
  private final val ClientRelayRole = "client-relay"
  private final val AnalyticsRole   = "analytics"

  class TransactIoToFuture(ec: ExecutionContext) {
    def apply[A](transactor: Transactor[IO])(io: ConnectionIO[A]): Future[A] =
      (for {
        a <- io.transact(transactor)
        _ <- IO.shift(ec)
      } yield a).unsafeToFuture()
  }

  def main(args: Array[String]): Unit = {
    val config                        = ConfigFactory.parseString(s"""
        |akka {
        |  loggers = ["akka.event.slf4j.Slf4jLogger"]
        |  loglevel = "DEBUG"
        |  logging-filter = "akka.event.slf4j.Slf4jLoggingFilter"
        |  actor {
        |    provider = "akka.cluster.ClusterActorRefProvider"
        |    serializers {
        |      zone-record = "com.dhpcs.liquidity.server.serialization.ZoneRecordSerializer"
        |      zone-validator-message = "com.dhpcs.liquidity.server.serialization.ZoneValidatorMessageSerializer"
        |      zone-monitor-message = "com.dhpcs.liquidity.server.serialization.ZoneMonitorMessageSerializer"
        |      client-connection-message = "com.dhpcs.liquidity.server.serialization.ClientConnectionMessageSerializer"
        |      client-monitor-message = "com.dhpcs.liquidity.server.serialization.ClientMonitorMessageSerializer"
        |    }
        |    serialization-bindings {
        |      "com.dhpcs.liquidity.persistence.zone.ZoneRecord" = zone-record
        |      "com.dhpcs.liquidity.actor.protocol.zonevalidator.SerializableZoneValidatorMessage" = zone-validator-message
        |      "com.dhpcs.liquidity.actor.protocol.zonemonitor.SerializableZoneMonitorMessage" = zone-monitor-message
        |      "com.dhpcs.liquidity.actor.protocol.clientconnection.SerializableClientConnectionMessage" = client-connection-message
        |      "com.dhpcs.liquidity.actor.protocol.clientmonitor.SerializableClientMonitorMessage" = client-monitor-message
        |    }
        |    allow-java-serialization = off
        |  }
        |  remote.netty.tcp {
        |    hostname = "${System.getenv("AKKA_HOSTNAME")}"
        |    bind-hostname = "0.0.0.0"
        |  }
        |  cluster.metrics.enabled = off
        |  extensions += "akka.persistence.Persistence"
        |  persistence {
        |    journal {
        |      auto-start-journals = ["jdbc-journal"]
        |      plugin = "jdbc-journal"
        |    }
        |    snapshot-store {
        |      auto-start-snapshot-stores = ["jdbc-snapshot-store"]
        |      plugin = "jdbc-snapshot-store"
        |    }
        |  }
        |  http.server.remote-address-header = on
        |}
        |jdbc-journal {
        |  slick = $${slick}
        |  event-adapters {
        |    zone-event = "com.dhpcs.liquidity.server.ZoneEventAdapter"
        |  }
        |  event-adapter-bindings {
        |    "com.dhpcs.liquidity.persistence.zone.ZoneEventEnvelope" = zone-event
        |  }
        |}
        |jdbc-snapshot-store.slick = $${slick}
        |jdbc-read-journal.slick = $${slick}
        |slick {
        |  profile = "slick.jdbc.MySQLProfile$$"
        |  db {
        |    driver = "com.mysql.jdbc.Driver"
        |    url = "${System.getenv("MYSQL_JOURNAL_URL")}"
        |    user = "${System.getenv("MYSQL_JOURNAL_USERNAME")}"
        |    password = "${System.getenv("MYSQL_JOURNAL_PASSWORD")}"
        |    maxConnections = 2
        |  }
        |}
      """.stripMargin).resolve()
    implicit val system: ActorSystem  = ActorSystem("liquidity", config)
    implicit val mat: Materializer    = ActorMaterializer()
    implicit val ec: ExecutionContext = ExecutionContext.global
    val clusterHttpManagement         = ClusterHttpManagement(Cluster(system))
    clusterHttpManagement.start()
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseClusterExitingDone, "clusterHttpManagementStop")(() =>
      clusterHttpManagement.stop())
    val analyticsTransactor = (for {
      analyticsTransactor <- HikariTransactor[IO](
        driverClassName = "com.mysql.jdbc.Driver",
        url = System.getenv("MYSQL_ANALYTICS_URL"),
        user = System.getenv("MYSQL_ANALYTICS_USERNAME"),
        pass = System.getenv("MYSQL_ANALYTICS_PASSWORD")
      )
      _ <- analyticsTransactor.configure(_.setMaximumPoolSize(2))
    } yield analyticsTransactor).unsafeRunSync()
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseServiceUnbind, "liquidityServerUnbind")(() =>
      for (_ <- analyticsTransactor.shutdown.unsafeToFuture()) yield Done)
    val server = new LiquidityServer(
      analyticsTransactor,
      pingInterval = 30.seconds,
      httpInterface = "0.0.0.0",
      httpPort = 80
    )
    val httpBinding = server.bindHttp()
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseServiceUnbind, "liquidityServerUnbind")(() =>
      for (_ <- httpBinding.flatMap(_.unbind())) yield Done)
  }
}

class LiquidityServer(analyticsTransactor: Transactor[IO],
                      pingInterval: FiniteDuration,
                      httpInterface: String,
                      httpPort: Int)(implicit system: ActorSystem, mat: Materializer)
    extends HttpController {

  private[this] val readJournal = PersistenceQuery(system).readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier)

  private[this] implicit val scheduler: Scheduler = system.scheduler
  private[this] implicit val ec: ExecutionContext = system.dispatcher
  private[this] implicit val askTimeout: Timeout  = Timeout(30.seconds)
  private[this] val transactIoToFuture = new TransactIoToFuture(
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool()))

  private[this] val clientMonitor = system.spawn(ClientMonitorActor.behavior, "clientMonitor")
  private[this] val zoneMonitor   = system.spawn(ZoneMonitorActor.behavior, "zoneMonitor")

  private[this] val zoneValidatorShardRegion = ClusterSharding(system.toTyped).spawn(
    behavior = ZoneValidatorActor.shardingBehavior,
    entityProps = Props.empty,
    typeKey = ZoneValidatorActor.ShardingTypeName,
    settings = ClusterShardingSettings(system.toTyped).withRole(ZoneHostRole),
    messageExtractor = ZoneValidatorActor.messageExtractor,
    handOffStopMessage = StopZone
  )

  ClusterSingleton(system.toTyped).spawn(
    behavior = ZoneAnalyticsActor.singletonBehavior(readJournal, analyticsTransactor, transactIoToFuture),
    singletonName = "zoneAnalyticsSingleton",
    props = Props.empty,
    settings = ClusterSingletonSettings(system.toTyped).withRole(AnalyticsRole),
    terminationMessage = StopZoneAnalytics
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
              ProtoBinding[ZoneEventEnvelope, proto.persistence.zone.ZoneEventEnvelope, ActorRefResolver]
                .asProto(zoneEventEnvelope)(ActorRefResolver(system.toTyped))
          }
          HttpController.EventEnvelope(sequenceNr, protoEvent)
      }

  override protected[this] def zoneState(zoneId: ZoneId): Future[proto.persistence.zone.ZoneState] = {
    val zoneState: Future[ZoneState] = zoneValidatorShardRegion ? (GetZoneStateCommand(_, zoneId))
    zoneState.map(
      ProtoBinding[ZoneState, proto.persistence.zone.ZoneState, ActorRefResolver]
        .asProto(_)(ActorRefResolver(system.toTyped)))
  }

  override protected[this] def webSocketApi(remoteAddress: InetAddress): Flow[Message, Message, NotUsed] =
    ClientConnectionActor.webSocketFlow(
      behavior = ClientConnectionActor.behavior(pingInterval, zoneValidatorShardRegion, remoteAddress)
    )

  override protected[this] def getActiveClientSummaries: Future[Set[ActiveClientSummary]] =
    clientMonitor ? GetActiveClientSummaries

  override protected[this] def getActiveZoneSummaries: Future[Set[ActiveZoneSummary]] =
    zoneMonitor ? GetActiveZoneSummaries

  override protected[this] def getZone(zoneId: ZoneId): Future[Option[Zone]] =
    transactIoToFuture(analyticsTransactor)(SqlAnalyticsStore.ZoneStore.retrieveOption(zoneId))

  override protected[this] def getBalances(zoneId: ZoneId): Future[Map[AccountId, BigDecimal]] =
    transactIoToFuture(analyticsTransactor)(SqlAnalyticsStore.AccountsStore.retrieveAllBalances(zoneId))

}
