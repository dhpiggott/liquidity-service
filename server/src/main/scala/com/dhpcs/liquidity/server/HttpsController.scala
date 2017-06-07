package com.dhpcs.liquidity.server

import java.security.interfaces.RSAPublicKey

import akka.NotUsed
import akka.http.scaladsl.model.StatusCodes.BadRequest
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.`Tls-Session-Info`
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.Flow
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.server.HttpsController._

object HttpsController {
  private final val RequiredClientKeySize = 2048
}

trait HttpsController {

  protected[this] def httpsRoutes(enableClientRelay: Boolean): Route =
    if (enableClientRelay) legacyWs else reject

  private[this] def legacyWs: Route =
    path("ws")(extractClientIP(ip =>
      headerValueByType[`Tls-Session-Info`](())(_.peerCertificates.headOption match {
        case Some(certificate) =>
          certificate.getPublicKey match {
            case rsaPublicKey: RSAPublicKey if rsaPublicKey.getModulus.bitLength == RequiredClientKeySize =>
              handleWebSocketMessages(legacyWebSocketApi(ip, PublicKey(rsaPublicKey.getEncoded)))
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

  protected[this] def legacyWebSocketApi(ip: RemoteAddress, publicKey: PublicKey): Flow[Message, Message, NotUsed]

}
