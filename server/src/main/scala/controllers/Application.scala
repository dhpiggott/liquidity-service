package controllers

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPublicKey
import javax.inject._

import actors.ClientsMonitor.{ActiveClientsSummary, GetActiveClientsSummary}
import actors.ZonesMonitor._
import actors.{ClientConnection, ClientsMonitor, ZoneValidator, ZonesMonitor}
import akka.actor.ActorSystem
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.pattern.ask
import akka.stream.{Materializer, OverflowStrategy}
import akka.util.Timeout
import com.dhpcs.liquidity.protocol.PublicKey
import controllers.Application._
import okio.ByteString
import org.apache.commons.codec.binary.Base64
import play.api.http.ContentTypes
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json
import play.api.libs.streams.ActorFlow
import play.api.mvc._

import scala.concurrent.Future
import scala.concurrent.duration._

object Application {

  val RequiredKeyLength = 2048

  private val PemCertStringMarkers = Seq(
    ("-----BEGIN CERTIFICATE-----", "-----END CERTIFICATE-----")
  )

  private def getPublicKey(headers: Headers) =
    headers.get("X-SSL-Client-Cert").fold[Either[String, PublicKey]](
      Left("Client certificate not presented")
    ) { pemString =>
      PemCertStringMarkers.collectFirst {
        case marker if pemString.startsWith(marker._1) && pemString.endsWith(marker._2) =>
          pemString.stripPrefix(marker._1).stripSuffix(marker._2)
      }.fold[Either[String, PublicKey]](
        Left("Client certificate PEM string is not valid")
      )(pemData =>
        CertificateFactory.getInstance("X.509").generateCertificate(
          new ByteArrayInputStream(
            Base64.decodeBase64(
              pemData
            )
          )
        ).getPublicKey match {
          case rsaPublicKey: RSAPublicKey =>
            if (rsaPublicKey.getModulus.bitLength != RequiredKeyLength) {
              Left("Invalid client public key length")
            } else {
              Right(PublicKey(rsaPublicKey.getEncoded))
            }
          case _ =>
            Left("Invalid client public key type")
        })
    }

}

class Application @Inject()(implicit system: ActorSystem, materializer: Materializer) extends Controller {

  private val clientsMonitor = system.actorOf(ClientsMonitor.props, "clients-monitor")
  private val zonesMonitor = system.actorOf(ZonesMonitor.props, "zones-monitor")

  private val zoneValidatorShardRegion = ClusterSharding(system).start(
    typeName = ZoneValidator.shardName,
    entityProps = ZoneValidator.props,
    settings = ClusterShardingSettings(system),
    extractEntityId = ZoneValidator.extractEntityId,
    extractShardId = ZoneValidator.extractShardId
  )

  private implicit val statusTimeout: Timeout = 5.seconds

  def status = Action.async {
    for {
      activeClientsSummary <- (clientsMonitor ? GetActiveClientsSummary).mapTo[ActiveClientsSummary]
      activeZonesSummary <- (zonesMonitor ? GetActiveZonesSummary).mapTo[ActiveZonesSummary]
      totalZonesCount <- (zonesMonitor ? GetZoneCount).mapTo[ZoneCount]
    } yield Ok(Json.prettyPrint(Json.obj(
      "clients" -> Json.obj(
        "count" -> activeClientsSummary.activeClientSummaries.size,
        "publicKeyFingerprints" -> activeClientsSummary.activeClientSummaries.map {
          case ClientConnection.ActiveClientSummary(publicKey) => publicKey.fingerprint
        }.sorted
      ),
      "totalZonesCount" -> totalZonesCount.count,
      "activeZones" -> Json.obj(
        "count" -> activeZonesSummary.activeZoneSummaries.size,
        "zones" -> activeZonesSummary.activeZoneSummaries.toSeq.sortBy(_.zoneId.id).map {
          case ZoneValidator.ActiveZoneSummary(zoneId, metadata, members, accounts, transactions, clientConnections) =>
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
    ))).as(ContentTypes.JSON)
  }

  def ws = WebSocket.acceptOrResult[String, String] { request: RequestHeader =>
    Future.successful(
      getPublicKey(request.headers) match {
        case Left(error) => Left(
          BadRequest(error)
        )
        case Right(publicKey) => Right(
          ActorFlow.actorRef(
            ClientConnection.props(publicKey, zoneValidatorShardRegion),
            bufferSize = 16,
            overflowStrategy = OverflowStrategy.fail
          )
        )
      }
    )
  }

}
