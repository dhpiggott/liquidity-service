package com.dhpcs.liquidity.service

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.ActorRefResolver
import akka.actor.{ActorSystem, CoordinatedShutdown, Scheduler}
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.typed._
import akka.discovery.awsapi.ecs.AsyncEcsServiceDiscovery
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.StandardRoute
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.persistence.query._
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.Timeout
import akka.Done
import cats.effect.{ContextShift, IO, Resource}
import cats.implicits._
import com.dhpcs.liquidity.actor.protocol.ProtoBindings._
import com.dhpcs.liquidity.actor.protocol.liquidityserver.ZoneResponseEnvelope
import com.dhpcs.liquidity.actor.protocol.zonevalidator._
import com.dhpcs.liquidity.persistence.zone._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.service.LiquidityServer._
import com.dhpcs.liquidity.service.SqlBindings._
import com.dhpcs.liquidity.service.actor.ZoneAnalyticsActor.StopZoneAnalytics
import com.dhpcs.liquidity.service.actor._
import com.typesafe.config.ConfigFactory
import doobie.hikari._
import doobie.implicits._
import doobie.util.ExecutionContexts
import doobie.util.transactor.Transactor
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object LiquidityServer {

  private final val ZoneHostRole = "zone-host"
  private final val ClientRelayRole = "client-relay"
  private final val AnalyticsRole = "analytics"

  private[this] val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val mysqlHostname = sys.env("MYSQL_HOSTNAME")
    val mysqlUsername = sys.env("MYSQL_USERNAME")
    val mysqlPassword = sys.env("MYSQL_PASSWORD")
    val privateAddress =
      AsyncEcsServiceDiscovery.getContainerAddress match {
        case Left(error) =>
          log.error(s"$error Halting.")
          sys.exit(1)

        case Right(value) =>
          value
      }
    val config = ConfigFactory
      .systemProperties()
      .withFallback(
        ConfigFactory
          .parseString(s"""
               |akka {
               |  loggers = ["akka.event.slf4j.Slf4jLogger"]
               |  loglevel = "DEBUG"
               |  logging-filter = "akka.event.slf4j.Slf4jLoggingFilter"
               |  actor {
               |    provider = "cluster"
               |    serializers {
               |      zone-record = "com.dhpcs.liquidity.service.serialization.ZoneRecordSerializer"
               |      client-connection-message = "com.dhpcs.liquidity.service.serialization.ClientConnectionMessageSerializer"
               |      liquidity-server-message = "com.dhpcs.liquidity.service.serialization.LiquidityServerMessageSerializer"
               |      zone-validator-message = "com.dhpcs.liquidity.service.serialization.ZoneValidatorMessageSerializer"
               |    }
               |    serialization-bindings {
               |      "com.dhpcs.liquidity.persistence.zone.ZoneRecord" = zone-record
               |      "com.dhpcs.liquidity.actor.protocol.clientconnection.SerializableClientConnectionMessage" = client-connection-message
               |      "com.dhpcs.liquidity.actor.protocol.liquidityserver.LiquidityServerMessage" = liquidity-server-message
               |      "com.dhpcs.liquidity.actor.protocol.zonevalidator.SerializableZoneValidatorMessage" = zone-validator-message
               |    }
               |    allow-java-serialization = off
               |  }
               |  management.http {
               |    hostname = "${privateAddress.getHostAddress}"
               |    base-path = "akka-management"
               |    route-providers-read-only = false
               |  }
               |  remote.artery {
               |    enabled = on
               |    transport = tcp
               |    canonical.hostname = "${privateAddress.getHostAddress}"
               |  }
               |  cluster.jmx.enabled = off
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
               |  http.server {
               |    preview.enable-http2 = on
               |    remote-address-header = on
               |    idle-timeout = 10s
               |  }
               |}
               |jdbc-journal.slick = $${slick}
               |jdbc-snapshot-store.slick = $${slick}
               |jdbc-read-journal.slick = $${slick}
               |slick {
               |  profile = "slick.jdbc.MySQLProfile$$"
               |  db {
               |    driver = "com.mysql.cj.jdbc.Driver"
               |    url = "${urlForDatabase(mysqlHostname, "liquidity_journal")}"
               |    user = "$mysqlUsername"
               |    password = "$mysqlPassword"
               |    maxConnections = 2
               |    numThreads = 2
               |  }
               |}
             """.stripMargin)
      )
      .resolve()
    implicit val contextShift: ContextShift[IO] =
      IO.contextShift(ExecutionContext.global)
    val administratorsTransactorResource = for {
      connectEc <- ExecutionContexts.fixedThreadPool[IO](2)
      transactionEc <- ExecutionContexts.cachedThreadPool[IO]
      administratorsTransactor <- HikariTransactor.newHikariTransactor[IO](
        driverClassName = "com.mysql.cj.jdbc.Driver",
        url = urlForDatabase(mysqlHostname, "liquidity_administrators"),
        user = mysqlUsername,
        pass = mysqlPassword,
        connectEc,
        transactionEc
      )
      _ <- Resource.liftF(
        administratorsTransactor.configure(hikariDataSource =>
          IO(hikariDataSource.setMaximumPoolSize(2)))
      )
    } yield administratorsTransactor
    val analyticsTransactorResource = for {
      connectEc <- ExecutionContexts.fixedThreadPool[IO](2)
      transactionEc <- ExecutionContexts.cachedThreadPool[IO]
      analyticsTransactor <- HikariTransactor.newHikariTransactor[IO](
        driverClassName = "com.mysql.cj.jdbc.Driver",
        url = urlForDatabase(mysqlHostname, "liquidity_analytics"),
        user = mysqlUsername,
        pass = mysqlPassword,
        connectEc,
        transactionEc
      )
      _ <- Resource.liftF(
        analyticsTransactor.configure(hikariDataSource =>
          IO(hikariDataSource.setMaximumPoolSize(2)))
      )
    } yield analyticsTransactor
    administratorsTransactorResource
      .use { administratorsTransactor =>
        analyticsTransactorResource.use { analyticsTransactor =>
          implicit val system: ActorSystem = ActorSystem("liquidity", config)
          implicit val mat: Materializer = ActorMaterializer()
          implicit val ec: ExecutionContext = ExecutionContext.global
          val akkaManagement = AkkaManagement(system)
          akkaManagement.start()
          CoordinatedShutdown(system).addTask(
            CoordinatedShutdown.PhaseClusterExitingDone,
            "akkaManagementStop")(() => akkaManagement.stop())
          ClusterBootstrap(system).start()
          val server = new LiquidityServer(
            administratorsTransactor,
            analyticsTransactor,
            pingInterval = 5.seconds,
            httpInterface = privateAddress.getHostAddress,
            httpPort = 8080
          )
          val httpBinding = server.bindHttp()
          CoordinatedShutdown(system).addTask(
            CoordinatedShutdown.PhaseServiceUnbind,
            "liquidityServerUnbind")(() =>
            httpBinding.flatMap(_.terminate(5.seconds).map(_ => Done)))
          IO.fromFuture(IO(system.whenTerminated.map(_ => ())))
        }
      }
      .unsafeRunSync()
  }

  private[this] def urlForDatabase(hostname: String, database: String): String =
    s"jdbc:mysql://$hostname/$database?" +
      "useSSL=false&" +
      "cacheCallableStmts=true&" +
      "cachePrepStmts=true&" +
      "cacheResultSetMetadata=true&" +
      "cacheServerConfiguration=true&" +
      "useLocalSessionState=true&" +
      "useServerPrepStmts=true"

}

class LiquidityServer(
    administratorsTransactor: Transactor[IO],
    analyticsTransactor: Transactor[IO],
    pingInterval: FiniteDuration,
    httpInterface: String,
    httpPort: Int)(implicit system: ActorSystem, mat: Materializer) {

  private[this] val readJournal = PersistenceQuery(system)
    .readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier)

  private[this] implicit val scheduler: Scheduler = system.scheduler
  private[this] implicit val ec: ExecutionContext = system.dispatcher

  private[this] val zoneValidatorShardRegion =
    ClusterSharding(system.toTyped).init(
      Entity(
        typeKey = ZoneValidatorActor.ShardingTypeName,
        createBehavior = entityContext =>
          ZoneValidatorActor.shardingBehavior(entityContext.entityId)
      ).withStopMessage(
          StopZone
        )
        .withSettings(
          ClusterShardingSettings(system.toTyped).withRole(ZoneHostRole))
        .withMessageExtractor(ZoneValidatorActor.messageExtractor)
    )

  ClusterSingleton(system.toTyped).init(
    SingletonActor(
      behavior =
        ZoneAnalyticsActor.singletonBehavior(readJournal, analyticsTransactor),
      name = "zoneAnalyticsSingleton"
    ).withSettings(
        ClusterSingletonSettings(system.toTyped).withRole(AnalyticsRole))
      .withStopMessage(StopZoneAnalytics)
  )

  private[this] val akkaManagement: StandardRoute =
    requestContext =>
      Source
        .single(requestContext.request)
        .via(Http().outgoingConnection(httpInterface, 8558))
        .runWith(Sink.head)
        .flatMap(requestContext.complete(_))

  private[this] val httpController = new HttpController(
    ready = requestContext =>
      akkaManagement(
        requestContext.withRequest(
          requestContext.request.withUri(Uri("/akka-management/ready")))
    ),
    alive = requestContext =>
      akkaManagement(
        requestContext.withRequest(
          requestContext.request.withUri(Uri("/akka-management/alive")))
    ),
    isAdministrator = publicKey =>
      sql"""
       SELECT 1
         FROM administrators
         WHERE public_key = $publicKey
      """
        .query[Int]
        .option
        .map(_.isDefined)
        .transact(administratorsTransactor)
        .unsafeToFuture(),
    akkaManagement = akkaManagement,
    events =
      (persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long) =>
        readJournal
          .eventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr)
          .map {
            case EventEnvelope(_, _, sequenceNr, event) =>
              val protoEvent = event match {
                case zoneEventEnvelope: ZoneEventEnvelope =>
                  ProtoBinding[ZoneEventEnvelope,
                               proto.persistence.zone.ZoneEventEnvelope,
                               ActorRefResolver]
                    .asProto(zoneEventEnvelope)(
                      ActorRefResolver(system.toTyped))
              }
              HttpController.EventEnvelope(sequenceNr, protoEvent)
        },
    zoneState = zoneId => {
      implicit val timeout: Timeout = Timeout(5.seconds)
      val zoneState
        : Future[ZoneState] = zoneValidatorShardRegion ? (GetZoneStateCommand(
        _,
        zoneId))
      zoneState.map(
        ProtoBinding[ZoneState,
                     proto.persistence.zone.ZoneState,
                     ActorRefResolver]
          .asProto(_)(ActorRefResolver(system.toTyped)))
    },
    execZoneCommand = (remoteAddress, publicKey, zoneId, zoneCommand) => {
      implicit val timeout: Timeout = Timeout(5.seconds)
      for {
        zoneResponseEnvelope <- zoneValidatorShardRegion
          .?[ZoneResponseEnvelope](
            ZoneCommandEnvelope(_,
                                zoneId,
                                remoteAddress,
                                publicKey,
                                correlationId = 0,
                                zoneCommand))
      } yield zoneResponseEnvelope.zoneResponse
    },
    zoneNotificationSource = (remoteAddress, publicKey, zoneId) =>
      ClientConnectionActor.zoneNotificationSource(
        zoneValidatorShardRegion,
        remoteAddress,
        publicKey,
        zoneId,
        system.spawnAnonymous(_)
    ),
    pingInterval = pingInterval
  )

  private def bindHttp(): Future[Http.ServerBinding] = Http().bindAndHandle(
    httpController.route(
      enableClientRelay =
        Cluster(system.toTyped).selfMember.roles.contains(ClientRelayRole)
    ),
    httpInterface,
    httpPort
  )

}
