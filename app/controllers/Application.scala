package controllers

import java.io.ByteArrayInputStream
import java.security.cert.{CertificateFactory, X509Certificate}

import actors.ClientConnection.AuthenticatedCommand
import actors.ClientIdentity.PostedAuthenticatedCommand
import actors.ClientIdentityManager.CreateConnectionForIdentity
import actors.{Actors, ClientIdentityManager}
import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import com.dhpcs.liquidity.models.{Event, PostedCommand, PublicKey}
import org.apache.commons.codec.binary.Base64
import play.api.Play.current
import play.api.libs.EventSource
import play.api.libs.EventSource.EventDataExtractor
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.iteratee.Enumerator
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

object Application extends Controller {

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

  def postAction = Action(parse.json) { request =>

    getPublicKey(request.headers) match {

      case Failure(exception) =>

        BadRequest(Json.obj("status" -> "KO", "error" -> s"public key not given: ${exception.getMessage}"))

      case Success(publicKey) =>

        request.body.validate[PostedCommand].fold(
          invalid = errors => BadRequest(Json.obj("status" -> "KO", "error" -> JsError.toFlatJson(errors))),
          valid = postedCommand => {
            Actors.clientIdentityManager ! PostedAuthenticatedCommand(
              postedCommand.connectionNumber,
              AuthenticatedCommand(publicKey, postedCommand.command)
            )
            Ok(Json.obj("status" -> "OK"))
          }
        )

    }

  }

  implicit val ConnectTimeout = Timeout(ClientIdentityManager.StoppingChildRetryDelay * 10)

  implicit val eventDataExtractor = EventDataExtractor[Event](
    message => Json.stringify(Json.toJson(message))
  )

  def getAction = Action.async { request =>

    getPublicKey(request.headers) match {

      case Failure(exception) =>

        Future.successful(
          BadRequest(Json.obj("status" -> "KO", "error" -> s"public key not given: ${exception.getMessage}"))
        )

      case Success(publicKey) =>

        (Actors.clientIdentityManager ? CreateConnectionForIdentity(publicKey, request.remoteAddress))
          .mapTo[Enumerator[Event]]
          .map(enumerator =>
          Ok.feed(enumerator.through(EventSource())).as("text/event-stream")
          ).recover { case _: AskTimeoutException => GatewayTimeout }

    }

  }

}
