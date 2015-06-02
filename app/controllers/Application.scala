package controllers

import java.io.ByteArrayInputStream
import java.security.cert.{CertificateFactory, X509Certificate}
import javax.inject._

import actors.ClientConnection
import akka.actor.ActorRef
import com.dhpcs.jsonrpc.JsonRpcMessage
import com.dhpcs.liquidity.models.PublicKey
import org.apache.commons.codec.binary.Base64
import play.api.Play.current
import play.api.mvc.WebSocket.FrameFormatter
import play.api.mvc._

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

class Application @Inject()(@Named("zone-registry") zoneRegistry: ActorRef) extends Controller {

  implicit val jsonRpcMessageFrameFormatter = FrameFormatter.jsonFrame[JsonRpcMessage]

  val pemCertStringMarkers = Seq(
    ("-----BEGIN CERTIFICATE-----", "-----END CERTIFICATE-----"),
    ("-----BEGIN TRUSTED CERTIFICATE-----", "-----END TRUSTED CERTIFICATE-----"),
    ("-----BEGIN X509 CERTIFICATE-----", "-----END X509 CERTIFICATE-----")
  )

  def getPublicKey(headers: Headers) = Try {
    val pemStringData = headers.get("X-SSL-Client-Cert").fold(
      scala.sys.error("Client certificate not present")
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
          Base64.decodeBase64(pemStringData.getOrElse(scala.sys.error("Client certificate PEM string is not valid")))
        )
      ).asInstanceOf[X509Certificate].getPublicKey.getEncoded
    )
  }

  def socket = WebSocket.tryAcceptWithActor[JsonRpcMessage, JsonRpcMessage] { request =>
    Future.successful(getPublicKey(request.headers) match {
      case Failure(exception) => Left(
        BadRequest(exception.getMessage)
      )
      case Success(publicKey) => Right(
        ClientConnection.props(publicKey, zoneRegistry)
      )
    })
  }

}
