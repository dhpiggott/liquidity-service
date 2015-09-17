package controllers

import java.io.{ByteArrayInputStream, IOException}
import java.net.InetSocketAddress
import java.nio.channels.SocketChannel
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPublicKey
import javax.inject._

import actors.{ClientConnection, ZoneValidator}
import akka.actor.ActorSystem
import akka.contrib.pattern.ClusterSharding
import akka.persistence.cassandra.CassandraPluginConfig
import com.dhpcs.liquidity.models.PublicKey
import controllers.Application._
import org.apache.commons.codec.binary.Base64
import play.api.Logger
import play.api.Play.current
import play.api.mvc._

import scala.concurrent.Future

object Application {

  private val PemCertStringMarkers = Seq(
    ("-----BEGIN CERTIFICATE-----", "-----END CERTIFICATE-----")
  )

  val RequiredKeyLength = 2048

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
        }
      )
    }

  private def isContactPointAvailable(contactPoint: InetSocketAddress) = {
    try {
      SocketChannel.open(contactPoint).close()
      Logger.info(s"Contact point $contactPoint is available")
      true
    } catch {
      case _: IOException =>
        Logger.info(s"Contact point $contactPoint is not available")
        false
    }
  }

}

class Application @Inject()(system: ActorSystem) extends Controller {

  {
    val config = new CassandraPluginConfig(system.settings.config.getConfig("cassandra-journal"))
    while (!config.contactPoints.exists(isContactPointAvailable)) {
      Logger.info("No contact points were available, retrying in 5 seconds...")
      Thread.sleep(5000)
    }
  }

  private val zoneValidatorShardRegion = ClusterSharding(system).start(
    typeName = ZoneValidator.shardName,
    entryProps = Some(ZoneValidator.props),
    idExtractor = ZoneValidator.idExtractor,
    shardResolver = ZoneValidator.shardResolver
  )

  def ws = WebSocket.tryAcceptWithActor[String, String] { request =>
    Future.successful(
      getPublicKey(request.headers) match {
        case Left(error) => Left(
          BadRequest(error)
        )
        case Right(publicKey) => Right(
          ClientConnection.props(publicKey, zoneValidatorShardRegion)
        )
      }
    )
  }

}
