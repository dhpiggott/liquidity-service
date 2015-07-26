package controllers

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import javax.inject._

import actors.ClientConnection
import akka.actor.ActorRef
import com.dhpcs.liquidity.models.PublicKey
import controllers.Application._
import org.apache.commons.codec.binary.Base64
import play.api.Play.current
import play.api.mvc._

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

object Application {

  private val pemCertStringMarkers = Seq(
    ("-----BEGIN CERTIFICATE-----", "-----END CERTIFICATE-----"),
    ("-----BEGIN TRUSTED CERTIFICATE-----", "-----END TRUSTED CERTIFICATE-----"),
    ("-----BEGIN X509 CERTIFICATE-----", "-----END X509 CERTIFICATE-----")
  )

  private def getPublicKey(headers: Headers) = Try {
    val pemStringData = headers.get("X-SSL-Client-Cert").fold(
      sys.error("Client certificate not present")
    ) {
      pemString =>
        pemCertStringMarkers.collectFirst {
          case marker if pemString.startsWith(marker._1) && pemString.endsWith(marker._2)
          => pemString.stripPrefix(marker._1).stripSuffix(marker._2)
        }
    }
    new PublicKey(
      CertificateFactory.getInstance("X.509").generateCertificate(
        new ByteArrayInputStream(
          Base64.decodeBase64(pemStringData.getOrElse(sys.error("Client certificate PEM string is not valid")))
        )
      ).getPublicKey.getEncoded
    )
  }

}

class Application @Inject()(@Named("zone-registry") zoneRegistry: ActorRef) extends Controller {

  def ws = WebSocket.tryAcceptWithActor[String, String] { request =>
    Future.successful(
      getPublicKey(request.headers) match {
        case Failure(exception) => Left(
          BadRequest(exception.getMessage)
        )
        case Success(publicKey) => Right(
          ClientConnection.props(publicKey, zoneRegistry)
        )
      }
    )
  }

}
