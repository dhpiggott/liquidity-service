package com.dhpcs.liquidity.service

import java.io.{ByteArrayInputStream, InputStreamReader}
import java.security.{KeyFactory, KeyStore, SecureRandom}
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.ActorRefResolver
import akka.actor.{ActorSystem, CoordinatedShutdown, Scheduler}
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.typed._
import akka.discovery.awsapi.ecs.AsyncEcsServiceDiscovery
import akka.http.scaladsl.{ConnectionContext, Http, Http2}
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.{Route, StandardRoute}
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.persistence.query._
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.{ByteString, Timeout}
import akka.Done
import akka.stream.alpakka.s3.scaladsl.S3
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
import javax.net.ssl._
import org.bouncycastle.openssl.PEMParser
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
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
    val maybeSubdomain = sys.env.get("SUBDOMAIN")
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
            httpInterface = privateAddress.getHostAddress
          )
          val httpBinding = server.bindHttp()
          CoordinatedShutdown(system).addTask(
            CoordinatedShutdown.PhaseServiceUnbind,
            "liquidityServerUnbind")(() =>
            httpBinding.flatMap(_.terminate(5.seconds).map(_ => Done)))
          maybeSubdomain match {
            case None =>
              ()

            case Some(subdomain) =>
              val region = sys.env("AWS_REGION")
              val keyManagerFactory = IO
                .fromFuture(IO(loadCertificate(region, subdomain)))
                .unsafeRunSync()
              val trustManagerFactory = TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm)
              trustManagerFactory.init(null: KeyStore)
              val sslContext = SSLContext.getInstance("TLS")
              sslContext.init(
                keyManagerFactory.getKeyManagers,
                trustManagerFactory.getTrustManagers,
                new SecureRandom
              )
              // TODO: Refine protocols and cipher suites
              val http2Binding =
                server.bindHttp2(ConnectionContext.https(sslContext))
              CoordinatedShutdown(system).addTask(
                CoordinatedShutdown.PhaseServiceUnbind,
                "liquidityServerUnbind")(() =>
                http2Binding.flatMap(_.terminate(5.seconds).map(_ => Done)))
          }
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

  private[this] def loadCertificate(region: String, subdomain: String)(
      implicit system: ActorSystem,
      mat: Materializer): Future[KeyManagerFactory] = {
    import system.dispatcher
    def load(key: String): Future[ByteString] =
      for {
        dataAndMetadata <- S3
          .download(
            bucket =
              s"$region.liquidity-certbot-runner-$subdomain.liquidityapp.com",
            key
          )
          .runWith(Sink.head)
        data <- dataAndMetadata match {
          case None =>
            Future.failed(
              new IllegalArgumentException(s"Object $key cannot be found"))

          case Some((data, _)) =>
            data.fold(ByteString.empty)(_ ++ _).runWith(Sink.head)
        }
      } yield data
    for {
      privateKeyBytes <- load(s"live/$subdomain.liquidityapp.com/privkey.pem")
      fullChainBytes <- load(s"live/$subdomain.liquidityapp.com/fullchain.pem")
      keyFactory = KeyFactory.getInstance("RSA")
      certificateFactory = CertificateFactory.getInstance("X.509")
      privateKey = keyFactory.generatePrivate(
        new PKCS8EncodedKeySpec(
          new PEMParser(
            new InputStreamReader(
              new ByteArrayInputStream(
                privateKeyBytes.toArray
              )
            )
          ).readPemObject().getContent
        )
      )
      fullChain = certificateFactory.generateCertificates(
        new ByteArrayInputStream(fullChainBytes.toArray)
      )
      keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
      _ = keyStore.load(null, Array.emptyCharArray)
      _ = keyStore.setKeyEntry(
        "identity",
        privateKey,
        Array.emptyCharArray,
        fullChain.asScala.toArray
      )
      keyManagerFactory = KeyManagerFactory.getInstance(
        KeyManagerFactory.getDefaultAlgorithm)
      _ = keyManagerFactory.init(
        keyStore,
        Array.emptyCharArray
      )
    } yield keyManagerFactory
  }
}

class LiquidityServer(
    administratorsTransactor: Transactor[IO],
    analyticsTransactor: Transactor[IO],
    httpInterface: String)(implicit system: ActorSystem, mat: Materializer) {

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

  private[this] val handler = Route.asyncHandler(
    new HttpController(
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
      """.query[Int]
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
      )
    ).route(
      enableClientRelay =
        Cluster(system.toTyped).selfMember.roles.contains(ClientRelayRole)
    )
  )

  private def bindHttp(): Future[Http.ServerBinding] =
    Http().bindAndHandleAsync(
      handler,
      httpInterface,
      port = 8080
    )

  private def bindHttp2(
      connectionContext: ConnectionContext): Future[Http.ServerBinding] =
    Http2().bindAndHandleAsync(
      handler,
      httpInterface,
      port = 8443,
      connectionContext
    )

}
