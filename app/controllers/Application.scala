package controllers

import java.io.ByteArrayInputStream
import java.security.cert.{CertificateFactory, X509Certificate}

import actors.{Actors, ClientConnection}
import com.dhpcs.liquidity.models.{Command, Event, PublicKey}
import org.apache.commons.codec.binary.Base64
import play.api.Play.current
import play.api.libs.json._
import play.api.mvc.WebSocket.FrameFormatter
import play.api.mvc._

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

object Application extends Controller {

  val pemCertStringMarkers = Seq(
    ("-----BEGIN CERTIFICATE-----", "-----END CERTIFICATE-----"),
    ("-----BEGIN TRUSTED CERTIFICATE-----", "-----END TRUSTED CERTIFICATE-----"),
    ("-----BEGIN X509 CERTIFICATE-----", "-----END X509 CERTIFICATE-----")
  )

  implicit def commandFrameFormatter: FrameFormatter[Command] = FrameFormatter.jsonFrame.transform(
    command => Json.toJson(command),
    json => Json.fromJson[Command](json).fold(
      invalid => scala.sys.error("Bad client command on WebSocket: " + invalid),
      valid => valid
    )
  )

  implicit def eventFrameFormatter: FrameFormatter[Event] = FrameFormatter.jsonFrame.transform(
    event => Json.toJson(event),
    json => Json.fromJson[Event](json).fold(
      invalid => scala.sys.error("Bad server event on WebSocket: " + invalid),
      valid => valid
    )
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

  def socket = WebSocket.tryAcceptWithActor[Command, Event] { request =>
    Future.successful(getPublicKey(request.headers) match {
      case Failure(exception) => Left(
        BadRequest(exception.getMessage)
      )
      case Success(publicKey) => Right(
        ClientConnection.props(publicKey, Actors.zoneRegistry)
      )
    })
  }

}
