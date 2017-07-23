package com.dhpcs.liquidity.server

import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl._

import akka.actor.{ActorPath, ActorSystem, CoordinatedShutdown, PoisonPill}
import akka.cluster.Cluster
import akka.cluster.http.management.ClusterHttpManagement
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings}
import akka.http.scaladsl.model.RemoteAddress
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.pattern.ask
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.stream.scaladsl.{Flow, Source}
import akka.stream.{ActorMaterializer, Materializer, TLSClientAuth}
import akka.util.Timeout
import akka.{Done, NotUsed}
import com.dhpcs.liquidity.actor.protocol._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.persistence._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.server.LiquidityServer._
import com.dhpcs.liquidity.server.actor.ClientsMonitorActor._
import com.dhpcs.liquidity.server.actor.ZonesMonitorActor._
import com.dhpcs.liquidity.server.actor._
import com.typesafe.config.ConfigFactory

import scala.collection.immutable.Seq
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
    val clusterHttpManagement         = ClusterHttpManagement(Cluster(system))
    clusterHttpManagement.start()
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseClusterExitingDone, "clusterHttpManagementStop")(() =>
      clusterHttpManagement.stop())
    val keyStore = KeyStore.getInstance("PKCS12")
    keyStore.load(
      getClass.getClassLoader.getResourceAsStream("liquidity.dhpcs.com.keystore.p12"),
      Array.emptyCharArray
    )
    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keyManagerFactory.init(
      keyStore,
      Array.emptyCharArray
    )
    val server = new LiquidityServer(
      pingInterval = FiniteDuration(config.getDuration("liquidity.server.ping-interval", SECONDS), SECONDS),
      keyManagers = keyManagerFactory.getKeyManagers,
      httpsInterface = config.getString("liquidity.server.https.interface"),
      httpsPort = config.getInt("liquidity.server.https.port"),
      httpInterface = config.getString("liquidity.server.http.interface"),
      httpPort = config.getInt("liquidity.server.http.port"),
      analyticsKeyspace = config.getString("liquidity.analytics.cassandra.keyspace")
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

class LiquidityServer(pingInterval: FiniteDuration,
                      keyManagers: Array[KeyManager],
                      httpsInterface: String,
                      httpsPort: Int,
                      httpInterface: String,
                      httpPort: Int,
                      analyticsKeyspace: String)(implicit system: ActorSystem, mat: Materializer)
    extends HttpController
    with LegacyHttpsController {

  private[this] val readJournal =
    PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

  private[this] implicit val askTimeout: Timeout = Timeout(5.seconds)
  private[this] implicit val ec                  = system.dispatcher

  private[this] val zoneValidatorShardRegion =
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

  private[this] val clientsMonitorActor = system.actorOf(ClientsMonitorActor.props, "clients-monitor")
  private[this] val zonesMonitorActor   = system.actorOf(ZonesMonitorActor.props, "zones-monitor")

  private[this] val futureAnalyticsStore =
    readJournal.session.underlying().flatMap(CassandraAnalyticsStore(analyticsKeyspace)(_, ec))

  if (Cluster(system).selfRoles.contains(AnalyticsRole)) {
    val streamFailureHandler = PartialFunction[Throwable, Unit] { t =>
      Console.err.println("Exiting due to stream failure")
      t.printStackTrace(Console.err)
      System.exit(1)
    }
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

  private[this] val httpsConnectionContext = {
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(
      keyManagers,
      Array(new X509TrustManager {
        override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
        override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
        override def getAcceptedIssuers: Array[X509Certificate]                                = Array.empty
      }),
      null
    )
    ConnectionContext.https(
      sslContext,
      enabledCipherSuites = Some(
        Seq(
          // Recommended by https://typesafehub.github.io/ssl-config/CipherSuites.html#id4
          "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256",
          "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
          // For Android 4.1 (see https://www.ssllabs.com/ssltest/viewClient.html?name=Android&version=4.1.1)
          "TLS_DHE_RSA_WITH_AES_256_CBC_SHA",
          "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA"
        )),
      enabledProtocols = Some(
        Seq(
          "TLSv1.2",
          "TLSv1.1",
          // For Android 4.1 (see https://www.ssllabs.com/ssltest/viewClient.html?name=Android&version=4.1.1)
          "TLSv1"
        )),
      clientAuth = Some(TLSClientAuth.Want)
    )
  }

  def bindHttp(): Future[Http.ServerBinding] = Http().bindAndHandle(
    httpRoutes(enableClientRelay = Cluster(system).selfRoles.contains(ClientRelayRole)),
    httpInterface,
    httpPort
  )

  def bindHttps(): Future[Http.ServerBinding] = Http().bindAndHandle(
    httpsRoutes(enableClientRelay = Cluster(system).selfRoles.contains(ClientRelayRole)),
    httpsInterface,
    httpsPort,
    httpsConnectionContext
  )

  override protected[this] def events(persistenceId: String,
                                      fromSequenceNr: Long,
                                      toSequenceNr: Long): Source[HttpController.GeneratedMessageEnvelope, NotUsed] =
    readJournal
      .eventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr)
      .map {
        case EventEnvelope(_, _, sequenceNr, event) =>
          val protoEvent = event match {
            case zoneEvent: ZoneEvent =>
              zoneEvent match {
                case zoneCreatedEvent: ZoneCreatedEvent =>
                  ProtoBinding[ZoneCreatedEvent, proto.persistence.ZoneCreatedEvent].asProto(zoneCreatedEvent)
                case zoneJoinedEvent: ZoneJoinedEvent =>
                  ProtoBinding[ZoneJoinedEvent, proto.persistence.ZoneJoinedEvent].asProto(zoneJoinedEvent)
                case zoneQuitEvent: ZoneQuitEvent =>
                  ProtoBinding[ZoneQuitEvent, proto.persistence.ZoneQuitEvent].asProto(zoneQuitEvent)
                case zoneNameChangedEvent: ZoneNameChangedEvent =>
                  ProtoBinding[ZoneNameChangedEvent, proto.persistence.ZoneNameChangedEvent]
                    .asProto(zoneNameChangedEvent)
                case memberCreatedEvent: MemberCreatedEvent =>
                  ProtoBinding[MemberCreatedEvent, proto.persistence.MemberCreatedEvent].asProto(memberCreatedEvent)
                case memberUpdatedEvent: MemberUpdatedEvent =>
                  ProtoBinding[MemberUpdatedEvent, proto.persistence.MemberUpdatedEvent].asProto(memberUpdatedEvent)
                case accountCreatedEvent: AccountCreatedEvent =>
                  ProtoBinding[AccountCreatedEvent, proto.persistence.AccountCreatedEvent].asProto(accountCreatedEvent)
                case accountUpdatedEvent: AccountUpdatedEvent =>
                  ProtoBinding[AccountUpdatedEvent, proto.persistence.AccountUpdatedEvent].asProto(accountUpdatedEvent)
                case transactionAddedEvent: TransactionAddedEvent =>
                  ProtoBinding[TransactionAddedEvent, proto.persistence.TransactionAddedEvent]
                    .asProto(transactionAddedEvent)
              }
          }
          HttpController.GeneratedMessageEnvelope(sequenceNr, protoEvent)
      }

  override protected[this] def zoneState(zoneId: ZoneId): Future[proto.model.ZoneState] =
    (zoneValidatorShardRegion ? GetZoneStateCommand(zoneId))
      .mapTo[GetZoneStateResponse]
      .map(response => ProtoBinding[ZoneState, proto.model.ZoneState].asProto(response.state))

  override protected[this] def webSocketApi(ip: RemoteAddress): Flow[Message, Message, NotUsed] =
    ClientConnectionActor.webSocketFlow(
      props = ClientConnectionActor.props(ip, zoneValidatorShardRegion, pingInterval)
    )

  override protected[this] def getActiveClientsSummary: Future[ActiveClientsSummary] =
    (clientsMonitorActor ? GetActiveClientsSummary).mapTo[ActiveClientsSummary]

  override protected[this] def getActiveZonesSummary: Future[ActiveZonesSummary] =
    (zonesMonitorActor ? GetActiveZonesSummary).mapTo[ActiveZonesSummary]

  override protected[this] def getZone(zoneId: ZoneId): Future[Option[Zone]] =
    futureAnalyticsStore.flatMap(_.zoneStore.retrieveOpt(zoneId))

  override protected[this] def getBalances(zoneId: ZoneId): Future[Map[AccountId, BigDecimal]] =
    futureAnalyticsStore.flatMap(_.balanceStore.retrieve(zoneId))

  override protected[this] def getClients(zoneId: ZoneId): Future[Map[ActorPath, (Long, PublicKey)]] =
    futureAnalyticsStore.flatMap(_.clientStore.retrieve(zoneId))

  override protected[this] def legacyWebSocketApi(ip: RemoteAddress,
                                                  publicKey: PublicKey): Flow[Message, Message, NotUsed] =
    LegacyClientConnectionActor.webSocketFlow(
      props = LegacyClientConnectionActor.props(ip, publicKey, zoneValidatorShardRegion, pingInterval)
    )

}
