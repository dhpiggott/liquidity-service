package com.dhpcs.liquidity.service

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStreamReader}
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.{KeyFactory, KeyStore, SecureRandom}
import java.util.zip.ZipInputStream

import akka.actor.typed.ActorRefResolver
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.actor.{ActorSystem, CoordinatedShutdown, Scheduler}
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.cluster.typed._
import akka.discovery.awsapi.ecs.AsyncEcsServiceDiscovery
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.{
  ConnectionContext,
  Http,
  Http2,
  HttpsConnectionContext
}
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.persistence.query._
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{ActorMaterializer, KillSwitches, Materializer}
import akka.util.{ByteString, Timeout}
import akka.{Done, NotUsed}
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

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object LiquidityServer {

  final case class CertBundle(privateKeyPem: ByteString,
                              fullChainPem: ByteString)

  private final val ZoneHostRole = "zone-host"
  private final val ClientRelayRole = "client-relay"
  private final val AnalyticsRole = "analytics"

  private[this] val log = LoggerFactory.getLogger(getClass)

  def loadHttpCertBundle(uri: Uri)(implicit system: ActorSystem,
                                   mat: Materializer,
                                   ec: ExecutionContext): Future[CertBundle] =
    for {
      response <- Http().singleRequest(
        HttpRequest(uri = uri)
      )
      certbundle <- readCertBundle(response.entity.dataBytes)
    } yield certbundle

  def httpsConnectionContext(
      keyManagerFactory: KeyManagerFactory,
      trustManagerFactory: TrustManagerFactory): HttpsConnectionContext = {
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(
      keyManagerFactory.getKeyManagers,
      trustManagerFactory.getTrustManagers,
      new SecureRandom
    )
    ConnectionContext.https(
      sslContext,
      enabledCipherSuites = Some(
        Seq(
          "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256",
          "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
          "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384",
          "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        )
      ),
      enabledProtocols = Some(
        Seq(
          "TLSv1.2"
        )
      )
    )
  }

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
          val akkaManagement = AkkaManagement(system).routes
          val akkaManagementHttpBinding = Http().bindAndHandleAsync(
            handler = Route.asyncHandler(akkaManagement),
            interface = "0.0.0.0",
            port = 8558
          )
          CoordinatedShutdown(system).addTask(
            CoordinatedShutdown.PhaseClusterExitingDone,
            "akkaManagementStop")(
            () =>
              akkaManagementHttpBinding.flatMap(
                _.terminate(5.seconds).map(_ => Done)
            )
          )
          ClusterBootstrap(system).start()
          val server = new LiquidityServer(
            administratorsTransactor,
            analyticsTransactor,
            akkaManagement
          )
          val loadCertBundle = maybeSubdomain match {
            case None =>
              () =>
                loadHttpCertBundle(Uri("http://certgen/certbundle.zip"))

            case Some(subdomain) =>
              val region = sys.env("AWS_REGION")
              () =>
                loadS3CertBundle(subdomain, region)
          }
          val (killSwitch, binding) =
            pollCertBundle(loadCertBundle, 12.hours)
              .viaMat(KillSwitches.single)(Keep.right)
              .foldAsync[Option[Http.ServerBinding]](None) {
                case (maybePreviousBinding, currentCertBundle) =>
                  for {
                    _ <- maybePreviousBinding match {
                      case None =>
                        Future.successful(Done)

                      case Some(previousBinding) =>
                        for {
                          _ <- Future.successful(Done)
                          _ = log.info(s"Unbinding $previousBinding.")
                          _ <- previousBinding.terminate(5.seconds)
                          _ = log.info("Unbound.")
                        } yield ()
                    }
                    currentBinding <- for {
                      _ <- Future.successful(Done)
                      _ = log.info(s"Binding with $currentCertBundle.")
                      currentBinding <- bind(server.handler, currentCertBundle)
                      _ = log.info("Bound.")
                    } yield currentBinding
                  } yield Some(currentBinding)
              }
              .toMat(Sink.last)(Keep.both)
              .run()
          CoordinatedShutdown(system).addTask(
            CoordinatedShutdown.PhaseServiceUnbind,
            "liquidityServerUnbind") { () =>
            killSwitch.shutdown()
            binding.flatMap(
              _.fold(Future.successful(Done))(
                _.terminate(5.seconds).map(_ => Done)
              )
            )
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

  private[this] def loadS3CertBundle(subdomain: String, region: String)(
      implicit mat: Materializer,
      ec: ExecutionContext): Future[CertBundle] =
    for {
      dataAndMetadata <- S3
        .download(
          bucket = s"$region.liquidity-certbot-runner-infrastructure-$subdomain",
          "certbundle.zip"
        )
        .runWith(Sink.head)
      zipBytesSource <- dataAndMetadata match {
        case None =>
          Future.failed(
            new IllegalArgumentException("certbundle.zip not found"))

        case Some((data, _)) =>
          Future.successful(data)
      }
      certbundle <- readCertBundle(zipBytesSource)
    } yield certbundle

  private[this] def readCertBundle(zipBytesSource: Source[ByteString, Any])(
      implicit mat: Materializer,
      ec: ExecutionContext): Future[CertBundle] = {
    @tailrec def unzip(zip: ZipInputStream,
                       buffer: Array[Byte] = new Array[Byte](4096),
                       entries: Map[String, ByteString] = Map.empty)
      : Map[String, ByteString] = {
      val entry = zip.getNextEntry
      if (entry == null) {
        entries
      } else {
        @tailrec def readEntry(
            bytes: ByteArrayOutputStream = new ByteArrayOutputStream(
              buffer.length)): ByteString = {
          val read = zip.read(buffer)
          if (read == -1) {
            ByteString(bytes.toByteArray)
          } else {
            bytes.write(buffer, 0, read)
            readEntry(bytes)
          }
        }
        unzip(zip, buffer, entries + (entry.getName -> readEntry()))
      }
    }
    for {
      zipBytes <- zipBytesSource
        .fold(ByteString.empty)(_ ++ _)
        .runWith(Sink.head)
      zipEntries = unzip(
        new ZipInputStream(new ByteArrayInputStream(zipBytes.toArray))
      )
    } yield
      CertBundle(
        privateKeyPem = zipEntries("privkey.pem"),
        fullChainPem = zipEntries("fullchain.pem")
      )
  }

  private[this] def pollCertBundle(
      loadCertBundle: () => Future[CertBundle],
      interval: FiniteDuration): Source[CertBundle, NotUsed] = {
    Source
      .fromFuture(loadCertBundle())
      .flatMapConcat(
        initialCertBundle =>
          Source
            .single(initialCertBundle)
            .concat(
              Source
                .single(initialCertBundle)
                .concat(
                  Source
                    .tick(interval, interval, ())
                    .mapAsync(1)(_ => loadCertBundle())
                )
                .via(
                  // Filter identical certbundles so we don't emit a new
                  // element in the common case - thus not triggering an HTTP
                  // rebind (which drops client connections, and so shouldn't
                  // be done except where genuinely necessary because the cert
                  // has changed).
                  Flow[CertBundle]
                    .sliding(2, 1)
                    .mapConcat {
                      case Seq(previousCertBundle, currentCertBundle) =>
                        if (currentCertBundle == previousCertBundle) Seq.empty
                        else Seq(currentCertBundle)

                      case Seq(_) =>
                        Seq.empty
                    }
                )
          ))
  }

  private[this] def bind(handler: HttpRequest => Future[HttpResponse],
                         certBundle: CertBundle)(
      implicit system: ActorSystem,
      mat: Materializer): Future[Http.ServerBinding] = {
    val trustManagerFactory = TrustManagerFactory
      .getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trustManagerFactory.init(
      null: KeyStore
    )
    Http2().bindAndHandleAsync(
      handler,
      interface = "0.0.0.0",
      port = 8443,
      httpsConnectionContext(
        keyManagerFactory(certBundle),
        trustManagerFactory
      )
    )
  }

  private[this] def keyManagerFactory(
      certBundle: CertBundle): KeyManagerFactory = {
    val keyFactory = KeyFactory.getInstance("RSA")
    val privateKey = keyFactory.generatePrivate(
      new PKCS8EncodedKeySpec(
        new PEMParser(
          new InputStreamReader(
            new ByteArrayInputStream(certBundle.privateKeyPem.toArray)
          )
        ).readPemObject().getContent
      )
    )
    val certificateFactory = CertificateFactory.getInstance("X.509")
    val fullChain = certificateFactory.generateCertificates(
      new ByteArrayInputStream(certBundle.fullChainPem.toArray)
    )
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
    keyStore.load(null, Array.emptyCharArray)
    keyStore.setKeyEntry(
      "identity",
      privateKey,
      Array.emptyCharArray,
      fullChain.asScala.toArray
    )
    val keyManagerFactory =
      KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keyManagerFactory.init(
      keyStore,
      Array.emptyCharArray
    )
    keyManagerFactory
  }
}

class LiquidityServer(
    administratorsTransactor: Transactor[IO],
    analyticsTransactor: Transactor[IO],
    akkaManagement: Route)(implicit system: ActorSystem, mat: Materializer) {

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

  private val handler = Route.asyncHandler(
    new HttpController(
      ready = requestContext =>
        akkaManagement(requestContext.withUnmatchedPath(Uri.Path("/ready"))),
      alive = requestContext =>
        akkaManagement(requestContext.withUnmatchedPath(Uri.Path("/alive"))),
      akkaManagement = akkaManagement,
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

}
