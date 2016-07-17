package com.dhpcs.liquidity.server

import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl._

import actors.ClientsMonitor.{ActiveClientsSummary, GetActiveClientsSummary}
import actors.ZonesMonitor.{ActiveZonesSummary, GetActiveZonesSummary, GetZoneCount, ZoneCount}
import actors.{ClientConnection, ClientsMonitor, ZoneValidator, ZonesMonitor}
import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.`Tls-Session-Info`
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.model.{ContentType, HttpEntity, RemoteAddress}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.pattern.ask
import akka.stream.scaladsl.Flow
import akka.stream.{ActorMaterializer, Materializer, TLSClientAuth}
import akka.util.Timeout
import com.dhpcs.liquidity.models.PublicKey
import com.dhpcs.liquidity.server.LiquidityServer._
import com.typesafe.config.{Config, ConfigFactory}
import okio.ByteString
import play.api.libs.json.{JsObject, Json}

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object LiquidityServer {
  private final val KeyStoreFilename = "liquidity.dhpcs.com.keystore"
  private final val EnabledCipherSuites = Seq(
    "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
    "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"
  )
  private final val RequiredClientKeyLength = 2048

  def main(args: Array[String]): Unit = {
    val config = ConfigFactory.load
    implicit val system = ActorSystem("liquidity")
    implicit val materializer = ActorMaterializer()
    val zoneValidatorShardRegion = ClusterSharding(system).start(
      typeName = ZoneValidator.ShardName,
      entityProps = ZoneValidator.props,
      settings = ClusterShardingSettings(system),
      extractEntityId = ZoneValidator.extractEntityId,
      extractShardId = ZoneValidator.extractShardId
    )
    val keyStore = KeyStore.getInstance("JKS")
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
      zoneValidatorShardRegion,
      keyManagerFactory.getKeyManagers
    )
    sys.addShutdownHook {
      Await.result(server.shutdown(), Duration.Inf)
      Await.result(system.terminate(), Duration.Inf)
    }
  }
}

class LiquidityServer(config: Config,
                      zoneValidatorShardRegion: ActorRef,
                      keyManagers: Array[KeyManager])
                     (implicit system: ActorSystem, materializer: Materializer) extends LiquidityService {

  import system.dispatcher

  private[this] val clientsMonitor = system.actorOf(ClientsMonitor.props, "clients-monitor")
  private[this] val zonesMonitor = system.actorOf(ZonesMonitor.props, "zones-monitor")

  private[this] val httpsConnectionContext = {
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(
      keyManagers,
      Array(new X509TrustManager {
        override def getAcceptedIssuers: Array[X509Certificate] = Array()

        override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = ()

        override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
      }),
      null
    )
    ConnectionContext.https(
      sslContext,
      enabledCipherSuites = Some(EnabledCipherSuites),
      clientAuth = Some(TLSClientAuth.want)
    )
  }

  private[this] val binding = Http().bindAndHandle(
    route,
    config.getString("liquidity.http.interface"),
    config.getInt("liquidity.http.port"),
    httpsConnectionContext
  )

  def shutdown(): Future[Unit] = binding.flatMap(_.unbind()).map { _ =>
    clientsMonitor ! PoisonPill
    zonesMonitor ! PoisonPill
    ()
  }

  override protected[this] def getStatus: ToResponseMarshallable = {
    def clientsStatus(activeClientsSummary: ActiveClientsSummary): JsObject =
      Json.obj(
        "count" -> activeClientsSummary.activeClientSummaries.size,
        "publicKeyFingerprints" -> activeClientsSummary.activeClientSummaries.map {
          case ClientConnection.ActiveClientSummary(publicKey) => publicKey.fingerprint
        }.sorted
      )
    def activeZonesStatus(activeZonesSummary: ActiveZonesSummary): JsObject =
      Json.obj(
        "count" -> activeZonesSummary.activeZoneSummaries.size,
        "zones" -> activeZonesSummary.activeZoneSummaries.toSeq.sortBy(_.zoneId.id).map {
          case ZoneValidator.ActiveZoneSummary(
          zoneId,
          metadata,
          members, accounts,
          transactions,
          clientConnections
          ) =>
            Json.obj(
              "zoneIdFingerprint" -> ByteString.encodeUtf8(zoneId.id.toString).sha256.hex,
              "metadata" -> metadata,
              "members" -> Json.obj("count" -> members.size),
              "accounts" -> Json.obj("count" -> accounts.size),
              "transactions" -> Json.obj("count" -> transactions.size),
              "clientConnections" -> Json.obj(
                "count" -> clientConnections.size,
                "publicKeyFingerprints" -> clientConnections.map(_.fingerprint).toSeq.sorted
              )
            )
        }
      )
    implicit val timeout = Timeout(5.seconds)
    for {
      activeClientsSummary <- (clientsMonitor ? GetActiveClientsSummary).mapTo[ActiveClientsSummary]
      activeZonesSummary <- (zonesMonitor ? GetActiveZonesSummary).mapTo[ActiveZonesSummary]
      totalZonesCount <- (zonesMonitor ? GetZoneCount).mapTo[ZoneCount]
    } yield HttpEntity(
      ContentType(`application/json`),
      Json.prettyPrint(Json.obj(
        "clients" -> clientsStatus(activeClientsSummary),
        "totalZonesCount" -> totalZonesCount.count,
        "activeZones" -> activeZonesStatus(activeZonesSummary)
      ))
    )
  }

  override protected[this] def extractClientPublicKey(ip: RemoteAddress)(route: PublicKey => Route): Route =
    headerValueByType[`Tls-Session-Info`]()(sessionInfo =>
      sessionInfo.peerCertificates.headOption.map(_.getPublicKey).fold[Route](
        ifEmpty = complete(
          BadRequest,
          s"Client certificate not presented by ${ip.toOption.getOrElse("unknown")}"
        )
      ) {
        case rsaPublicKey: RSAPublicKey =>
          if (rsaPublicKey.getModulus.bitLength != RequiredClientKeyLength) {
            complete(
              BadRequest,
              s"Invalid client public key length from ${ip.toOption.getOrElse("unknown")}"
            )
          } else {
            route(
              PublicKey(rsaPublicKey.getEncoded)
            )
          }
        case _ =>
          complete(
            BadRequest,
            s"Invalid client public key type from ${ip.toOption.getOrElse("unknown")}"
          )
      }
    )

  override protected[this] def webSocketApi(ip: RemoteAddress, publicKey: PublicKey): Flow[Message, Message, Any] =
    ClientConnection.webSocketFlow(ip, publicKey, zoneValidatorShardRegion)
}
