package com.dhpcs.liquidity.server

import java.net.InetAddress
import java.security.interfaces.RSAPublicKey

import akka.NotUsed
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.`Tls-Session-Info`
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.Flow
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.server.LegacyHttpsController._

object LegacyHttpsController {
  private final val RequiredClientKeySize = 2048
}

trait LegacyHttpsController {

  protected[this] def httpsRoutes(enableClientRelay: Boolean): Route =
    if (enableClientRelay) legacyWs else reject

  private[this] def legacyWs: Route =
    path("ws")(extractClientIP(_.toOption match {
      case None =>
        complete(
          (InternalServerError, s"Could not extract remote address. Check akka.http.server.remote-address-header = on.")
        )
      case Some(remoteAddress) =>
        headerValueByType[`Tls-Session-Info`](())(_.peerCertificates.headOption match {
          case Some(certificate) =>
            certificate.getPublicKey match {
              case rsaPublicKey: RSAPublicKey if rsaPublicKey.getModulus.bitLength == RequiredClientKeySize =>
                handleWebSocketMessages(legacyWebSocketApi(remoteAddress, PublicKey(rsaPublicKey.getEncoded)))
              case _ =>
                complete(
                  (BadRequest, s"Invalid client public key from $remoteAddress")
                )
            }
          case None =>
            complete(
              (BadRequest, s"Client certificate not presented by $remoteAddress")
            )
        })
    }))

  protected[this] def legacyWebSocketApi(remoteAddress: InetAddress,
                                         publicKey: PublicKey): Flow[Message, Message, NotUsed]

}
