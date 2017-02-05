package com.dhpcs.liquidity.server

import akka.NotUsed
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.Flow
import com.dhpcs.liquidity.model.PublicKey
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}

trait HttpController {

  protected[this] def route(enableClientRelay: Boolean)(implicit ec: ExecutionContext): Route =
    status ~ (if (enableClientRelay) ws else reject)

  private[this] def status(implicit ec: ExecutionContext) = path("status")(
    get(
      complete(
        getStatus.map(toJsonResponse)
      )
    )
  )

  private[this] def ws = path("ws")(
    extractClientIP(
      ip => extractClientPublicKey(ip)(publicKey => handleWebSocketMessages(webSocketApi(ip, publicKey))))
  )

  protected[this] def getStatus: Future[JsValue]
  protected[this] def extractClientPublicKey(ip: RemoteAddress)(route: PublicKey => Route): Route
  protected[this] def webSocketApi(ip: RemoteAddress, publicKey: PublicKey): Flow[Message, Message, NotUsed]

  private[this] def toJsonResponse(body: JsValue): HttpResponse =
    HttpResponse(
      entity = HttpEntity(ContentType(`application/json`), Json.prettyPrint(body))
    )

}
