package controllers

import java.security.MessageDigest
import java.util

import actors.ClientConnection.AuthenticatedInboundMessage
import actors.ClientIdentity.PostedInboundAuthenticatedMessage
import actors.ClientIdentityManager.CreateConnectionForIdentity
import actors.{Actors, ClientIdentityManager}
import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import models._
import org.apache.commons.codec.binary.{Base64, Hex}
import play.api.Play.current
import play.api.libs.EventSource
import play.api.libs.EventSource.EventDataExtractor
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.iteratee.Enumerator
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.Future

object Application extends Controller {

  object PublicKey {

    implicit val publicKeyReads =
      __.read[String].map(publicKeyBase64 => new PublicKey(Base64.decodeBase64(publicKeyBase64)))

    implicit val publicKeyWrites = Writes[PublicKey] {
      publicKey => JsString(Base64.encodeBase64String(publicKey.value))

    }

  }

  class PublicKey(val value: Array[Byte]) {

    lazy val base64encoded = Base64.encodeBase64String(value)

    lazy val fingerprint = Hex.encodeHexString(MessageDigest.getInstance("SHA-1").digest(value))

    override def equals(that: Any) = that match {

      case that: PublicKey => util.Arrays.equals(this.value, that.value)

      case _ => false

    }

    override def toString = base64encoded

  }

  def getPublicKey(headers: Headers): Option[PublicKey] = {

    // TODO:
    // Extract client certificate using http://nginx.org/en/docs/http/ngx_http_ssl_module.html#ssl_verify_client
    // Get the public key from it (as per https://www.owasp.org/index.php/Certificate_and_Public_Key_Pinning#Public_Key)
    // using
    // http://stackoverflow.com/questions/9739121/convert-a-pem-formatted-string-to-a-java-security-cert-x509certificate
    // and http://stackoverflow.com/questions/6358555/obtaining-public-key-from-certificate
    Some(new PublicKey(Base64.decodeBase64("TODO")))
  }

  case class PostedInboundMessage(connectionNumber: Int, inboundMessage: InboundMessage)

  object PostedInboundMessage {

    implicit val postedInboundMessageReads = Json.reads[PostedInboundMessage]

  }

  def postAction = Action(parse.json) { request =>

    val maybePublicKey = getPublicKey(request.headers)

    maybePublicKey.fold(
      BadRequest(Json.obj("status" -> "KO", "error" -> "public key not given")))(
        publicKey => {

          request.body.validate[PostedInboundMessage].fold(
            invalid = errors => BadRequest(Json.obj("status" -> "KO", "error" -> JsError.toFlatJson(errors))),
            valid = postedInboundMessage => {
              Actors.clientIdentityManager ! PostedInboundAuthenticatedMessage(
                postedInboundMessage.connectionNumber,
                AuthenticatedInboundMessage(publicKey, postedInboundMessage.inboundMessage)
              )
              Ok(Json.obj("status" -> "OK"))
            }
          )

        }
      )

  }

  implicit val ConnectTimeout = Timeout(ClientIdentityManager.StoppingChildRetryDelay * 10)

  implicit val outboundMessageEventDataExtractor = EventDataExtractor[OutboundMessage](
    message => Json.stringify(Json.toJson(message))
  )

  def getAction = Action.async { request =>

    val maybePublicKey = getPublicKey(request.headers)

    maybePublicKey.fold(
      Future.successful(BadRequest(Json.obj("status" -> "KO", "error" -> "public key not given"))))(
        publicKey => {
          (Actors.clientIdentityManager ? CreateConnectionForIdentity(publicKey, request.remoteAddress))
            .mapTo[Enumerator[OutboundMessage]]
            .map(enumerator =>
            Ok.feed(enumerator.through(EventSource())).as("text/event-stream")
            ).recover { case _: AskTimeoutException => GatewayTimeout }
        }
      )

  }

}