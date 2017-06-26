package com.dhpcs.liquidity.server

import java.security.KeyStore
import java.security.cert.{CertificateException, X509Certificate}
import javax.net.ssl._

import akka.actor.{ActorPath, ActorRef, ActorSystem, CoordinatedShutdown, Deploy, PoisonPill}
import akka.cluster.Cluster
import akka.cluster.http.management.ClusterHttpManagement
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings}
import akka.http.scaladsl.model.RemoteAddress
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.pattern.ask
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.scaladsl.{CurrentPersistenceIdsQuery, ReadJournal}
import akka.stream.scaladsl.Flow
import akka.stream.{ActorMaterializer, Materializer, TLSClientAuth}
import akka.util.Timeout
import akka.{Done, NotUsed}
import com.dhpcs.liquidity.actor.protocol._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.server.LiquidityServer._
import com.dhpcs.liquidity.server.actor.ClientsMonitorActor._
import com.dhpcs.liquidity.server.actor.ZonesMonitorActor._
import com.dhpcs.liquidity.server.actor._
import com.trueaccord.scalapb.json.JsonFormat
import com.typesafe.config.{Config, ConfigFactory}
import okio.ByteString
import org.json4s.JsonAST.{JArray, JInt, JNull, JString}
import org.json4s.{JObject, JValue}

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.Future

object LiquidityServer {

  final val EnabledCipherSuites = Seq(
    // Recommended by https://typesafehub.github.io/ssl-config/CipherSuites.html#id4
    "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256",
    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
    // For Android 4.1 (see https://www.ssllabs.com/ssltest/viewClient.html?name=Android&version=4.1.1)
    "TLS_DHE_RSA_WITH_AES_256_CBC_SHA",
    "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA"
  )

  final val EnabledProtocols = Seq(
    "TLSv1.2",
    "TLSv1.1",
    // For Android 4.1 (see https://www.ssllabs.com/ssltest/viewClient.html?name=Android&version=4.1.1)
    "TLSv1"
  )

  private final val KeyStoreFilename = "liquidity.dhpcs.com.keystore.p12"

  private final val ZoneHostRole    = "zone-host"
  private final val ClientRelayRole = "client-relay"
  private final val AnalyticsRole   = "analytics"

  def main(args: Array[String]): Unit = {
    val config               = ConfigFactory.load
    implicit val system      = ActorSystem("liquidity")
    implicit val mat         = ActorMaterializer()
    val readJournal          = PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
    implicit val ec           = system.dispatcher
    val futureAnalyticsStore = readJournal.session.underlying().flatMap(CassandraAnalyticsStore(config)(_, ec))
    val streamFailureHandler = PartialFunction[Throwable, Unit] { t =>
      Console.err.println("Exiting due to stream failure")
      t.printStackTrace(Console.err)
      sys.exit(1)
    }
    val clusterHttpManagement = ClusterHttpManagement(Cluster(system))
    clusterHttpManagement.start()
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseClusterExitingDone, "clusterHttpManagementStop")(() =>
      clusterHttpManagement.stop())
    val zoneValidatorShardRegion =
      if (Cluster(system).selfRoles.contains(ZoneHostRole))
        ClusterSharding(system).start(
          typeName = ZoneValidatorActor.ShardTypeName,
          entityProps = ZoneValidatorActor.props,
          settings = ClusterShardingSettings(system).withRole(ZoneHostRole),
          extractEntityId = ZoneValidatorActor.extractEntityId,
          extractShardId = ZoneValidatorActor.extractShardId
        )
      else
        ClusterSharding(system).startProxy(
          typeName = ZoneValidatorActor.ShardTypeName,
          role = Some(ZoneHostRole),
          extractEntityId = ZoneValidatorActor.extractEntityId,
          extractShardId = ZoneValidatorActor.extractShardId
        )

    if (Cluster(system).selfRoles.contains(AnalyticsRole)) {
      val zoneAnalyticsShardRegion = ClusterSharding(system).start(
        typeName = ZoneAnalyticsActor.ShardTypeName,
        entityProps = ZoneAnalyticsActor.props(readJournal, futureAnalyticsStore, streamFailureHandler),
        settings = ClusterShardingSettings(system).withRole(AnalyticsRole),
        extractEntityId = ZoneAnalyticsActor.extractEntityId,
        extractShardId = ZoneAnalyticsActor.extractShardId
      )
      system.actorOf(
        ClusterSingletonManager.props(
          singletonProps = ZoneAnalyticsStarterActor.props(readJournal, zoneAnalyticsShardRegion, streamFailureHandler),
          terminationMessage = PoisonPill,
          settings =
            ClusterSingletonManagerSettings(system).withSingletonName("zone-analytics-starter").withRole(AnalyticsRole)
        ),
        name = "zone-analytics-starter-singleton"
      )
    }

    val keyStore = KeyStore.getInstance("PKCS12")
    keyStore.load(
      getClass.getClassLoader.getResourceAsStream(KeyStoreFilename),
      Array.emptyCharArray
    )
    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keyManagerFactory.init(
      keyStore,
      Array.emptyCharArray
    )
    val server = new LiquidityServer(
      config,
      readJournal,
      futureAnalyticsStore,
      zoneValidatorShardRegion,
      keyManagerFactory.getKeyManagers
    )
    val httpBinding  = server.bindHttp()
    val httpsBinding = server.bindHttps()
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseServiceUnbind, "liquidityServerUnbind")(() =>
      for {
        _ <- httpsBinding.flatMap(_.unbind())
        _ <- httpBinding.flatMap(_.unbind())
      } yield Done)
  }
}

class LiquidityServer(config: Config,
                      readJournal: ReadJournal with CurrentPersistenceIdsQuery,
                      futureAnalyticsStore: Future[CassandraAnalyticsStore],
                      zoneValidatorShardRegion: ActorRef,
                      keyManagers: Array[KeyManager])(implicit system: ActorSystem, mat: Materializer)
    extends HttpController
    with HttpsController {

  private[this] implicit val ec = system.dispatcher

  private[this] val clientsMonitorActor = system.actorOf(
    ClientsMonitorActor.props.withDeploy(Deploy.local),
    "clients-monitor"
  )
  private[this] val zonesMonitorActor = system.actorOf(
    ZonesMonitorActor.props(() => ZonesMonitorActor.zoneCount(readJournal)).withDeploy(Deploy.local),
    "zones-monitor"
  )

  private[this] val httpsConnectionContext = {
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(
      keyManagers,
      Array(new X509TrustManager {

        override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = ()

        override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit =
          throw new CertificateException

        override def getAcceptedIssuers: Array[X509Certificate] = Array.empty

      }),
      null
    )
    ConnectionContext.https(
      sslContext,
      enabledCipherSuites = Some(EnabledCipherSuites),
      enabledProtocols = Some(EnabledProtocols),
      clientAuth = Some(TLSClientAuth.Want)
    )
  }

  private[this] val pingInterval = FiniteDuration(
    config.getDuration("liquidity.server.ping-interval", SECONDS),
    SECONDS
  )

  def bindHttp(): Future[Http.ServerBinding] = Http().bindAndHandle(
    httpRoutes(enableClientRelay = Cluster(system).selfRoles.contains(ClientRelayRole)),
    config.getString("liquidity.server.http.interface"),
    config.getInt("liquidity.server.http.port")
  )

  def bindHttps(): Future[Http.ServerBinding] = Http().bindAndHandle(
    httpsRoutes(enableClientRelay = Cluster(system).selfRoles.contains(ClientRelayRole)),
    config.getString("liquidity.server.https.interface"),
    config.getInt("liquidity.server.https.port"),
    httpsConnectionContext
  )

  override protected[this] def getStatus: Future[JValue] = {
    def fingerprint(id: String): String = ByteString.encodeUtf8(id).sha256.hex
    def clientsStatus(activeClientsSummary: ActiveClientsSummary): JObject =
      JObject(
        "count" -> JInt(activeClientsSummary.activeClientSummaries.size),
        "publicKeyFingerprints" -> JArray(
          activeClientsSummary.activeClientSummaries.map(_.publicKey.fingerprint).toList.sorted.map(JString)
        )
      )
    def activeZonesStatus(activeZonesSummary: ActiveZonesSummary): JObject =
      JObject(
        "count" -> JInt(activeZonesSummary.activeZoneSummaries.size),
        "zones" -> JArray(
          activeZonesSummary.activeZoneSummaries.toSeq
            .sortBy(_.zoneId.id)
            .map {
              case ActiveZoneSummary(
                  zoneId,
                  metadata,
                  members,
                  accounts,
                  transactions,
                  clientConnections
                  ) =>
                JObject(
                  "zoneIdFingerprint" -> JString(fingerprint(zoneId.id.toString)),
                  "metadata"          -> metadata.map(JsonFormat.toJson(_)).getOrElse(JNull),
                  "members"           -> JObject("count" -> JInt(members.size)),
                  "accounts"          -> JObject("count" -> JInt(accounts.size)),
                  "transactions"      -> JObject("count" -> JInt(transactions.size)),
                  "clientConnections" -> JObject(
                    "count"                 -> JInt(clientConnections.size),
                    "publicKeyFingerprints" -> JArray(clientConnections.map(_.fingerprint).toList.sorted.map(JString))
                  )
                )
            }
            .toList)
      )
    def shardRegionStatus(shardRegionState: ShardRegion.CurrentShardRegionState): JObject =
      JObject(
        "count" -> JInt(shardRegionState.shards.size),
        "shards" -> JObject(
          shardRegionState.shards.toSeq
            .sortBy(_.shardId)
            .map {
              case ShardRegion.ShardState(shardId, entityIds) =>
                shardId -> JArray(entityIds.toList.sorted.map(fingerprint).map(JString))
            }
            .toList)
      )
    def clusterShardingStatus(clusterShardingStats: ShardRegion.ClusterShardingStats): JObject =
      JObject(
        "count" -> JInt(clusterShardingStats.regions.size),
        "regions" -> JObject(
          clusterShardingStats.regions.toSeq
            .sortBy { case (address, _) => address }
            .map {
              case (address, shardRegionStats) =>
                address.toString ->
                  JObject(shardRegionStats.stats.toSeq
                    .sortBy { case (shardId, _) => shardId }
                    .map {
                      case (shardId, entityCount) =>
                        shardId -> JInt(entityCount)
                    }
                    .toList)
            }
            .toList)
      )
    implicit val askTimeout = Timeout(5.seconds)
    for {
      activeClientsSummary <- (clientsMonitorActor ? GetActiveClientsSummary).mapTo[ActiveClientsSummary]
      activeZonesSummary   <- (zonesMonitorActor ? GetActiveZonesSummary).mapTo[ActiveZonesSummary]
      totalZonesCount      <- (zonesMonitorActor ? GetZoneCount).mapTo[ZoneCount]
      shardRegionState <- (zoneValidatorShardRegion ? ShardRegion.GetShardRegionState)
        .mapTo[ShardRegion.CurrentShardRegionState]
      clusterShardingStats <- (zoneValidatorShardRegion ? ShardRegion.GetClusterShardingStats(askTimeout.duration))
        .mapTo[ShardRegion.ClusterShardingStats]
    } yield
      JObject(
        "clients"         -> clientsStatus(activeClientsSummary),
        "totalZonesCount" -> JInt(totalZonesCount.count),
        "activeZones"     -> activeZonesStatus(activeZonesSummary),
        "shardRegions"    -> shardRegionStatus(shardRegionState),
        "clusterSharding" -> clusterShardingStatus(clusterShardingStats)
      )
  }

  override protected[this] def webSocketApi(ip: RemoteAddress): Flow[Message, Message, NotUsed] =
    ClientConnectionActor.webSocketFlow(
      props = ClientConnectionActor.props(ip, zoneValidatorShardRegion, pingInterval)
    )

  override protected[this] def legacyWebSocketApi(ip: RemoteAddress,
                                                  publicKey: PublicKey): Flow[Message, Message, NotUsed] =
    LegacyClientConnectionActor.webSocketFlow(
      props = LegacyClientConnectionActor.props(ip, publicKey, zoneValidatorShardRegion, pingInterval)
    )

  override protected[this] def getZone(zoneId: ZoneId): Future[Option[Zone]] =
    futureAnalyticsStore.flatMap(_.zoneStore.retrieveOpt(zoneId))

  override protected[this] def getBalances(zoneId: ZoneId): Future[Map[AccountId, BigDecimal]] =
    futureAnalyticsStore.flatMap(_.balanceStore.retrieve(zoneId))

  override protected[this] def getClients(zoneId: ZoneId): Future[Map[ActorPath, (Long, PublicKey)]] =
    futureAnalyticsStore.flatMap(_.clientStore.retrieve(zoneId))

}
