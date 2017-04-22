package com.dhpcs.liquidity.server

import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

import akka.NotUsed
import akka.actor.ActorPath
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model.StatusCodes.BadRequest
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.`Tls-Session-Info`
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.Flow
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.server.HttpController._
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}

object HttpController {
  private final val RequiredClientKeyLength = 2048
}

trait HttpController {

  protected[this] def route(enableClientRelay: Boolean)(implicit ec: ExecutionContext): Route =
    (if (enableClientRelay) ws else reject) ~ status ~ analytics

  private[this] def ws: Route =
    path("ws")(extractClientIP(ip =>
      headerValueByType[`Tls-Session-Info`](())(_.peerCertificates.headOption match {
        case Some(certificate) =>
          certificate.getPublicKey match {
            case rsaPublicKey: RSAPublicKey if rsaPublicKey.getModulus.bitLength == RequiredClientKeyLength =>
              handleWebSocketMessages(webSocketApi(ip, PublicKey(rsaPublicKey.getEncoded)))
            case _ =>
              complete(
                (BadRequest, s"Invalid client public key from ${ip.toOption.getOrElse("unknown")}")
              )
          }
        case None =>
          complete(
            (BadRequest, s"Client certificate not presented by ${ip.toOption.getOrElse("unknown")}")
          )
      })))

  private[this] def status(implicit ec: ExecutionContext): Route = path("status")(
    get(
      complete(
        getStatus.map(toJsonResponse)
      )
    )
  )

  private[this] def analytics(implicit ec: ExecutionContext): Route =
    pathPrefix("analytics")(
      pathPrefix("api")(
        pathPrefix("zone")(
          pathPrefix(JavaUUID)(
            id =>
              pathEnd(zone(id)) ~
                path("balances")(balances(id)) ~
                path("clients")(clients(id))))
      )
    )

  private[this] def zone(id: UUID)(implicit ec: ExecutionContext): Route =
    get(complete(zoneOpt(ZoneId(id)).map {
      case None       => HttpResponse(status = StatusCodes.NotFound)
      case Some(zone) => toJsonResponse(Json.toJson(zone))
    }))

  private[this] def balances(id: UUID)(implicit ec: ExecutionContext): Route =
    get(complete(balances(ZoneId(id)).map(balances =>
      toJsonResponse(Json.toJson(balances.map {
        case (accountId, balance) => accountId.id.toString -> balance
      })))))

  private[this] def clients(id: UUID)(implicit ec: ExecutionContext): Route =
    get(complete(clients(ZoneId(id)).map(clients =>
      toJsonResponse(Json.toJson(clients.map {
        case (actorPath, (lastJoined, publicKey)) =>
          actorPath.toSerializationFormat -> Json.obj(
            "lastJoined"  -> DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(lastJoined)),
            "fingerprint" -> publicKey.fingerprint)
      })))))

  protected[this] def webSocketApi(ip: RemoteAddress, publicKey: PublicKey): Flow[Message, Message, NotUsed]
  protected[this] def getStatus: Future[JsValue]
  protected[this] def zoneOpt(zoneId: ZoneId): Future[Option[Zone]]
  protected[this] def balances(zoneId: ZoneId): Future[Map[AccountId, BigDecimal]]
  protected[this] def clients(zoneId: ZoneId): Future[Map[ActorPath, (Long, PublicKey)]]

  private[this] def toJsonResponse(body: JsValue): HttpResponse =
    HttpResponse(
      entity = HttpEntity(ContentType(`application/json`), Json.prettyPrint(body))
    )

}
