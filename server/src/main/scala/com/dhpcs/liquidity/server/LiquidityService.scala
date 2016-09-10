package com.dhpcs.liquidity.server

import akka.NotUsed
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.RemoteAddress
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.Flow
import com.dhpcs.liquidity.model.PublicKey

trait LiquidityService {
  private[this] val status = path("status")(
    get(
      complete(
        getStatus
      )
    )
  )
  private[this] val ws = path("ws")(
    extractClientIP(ip =>
      extractClientPublicKey(ip)(publicKey =>
        handleWebSocketMessages(webSocketApi(ip, publicKey))
      )
    )
  )

  protected[this] val route = status ~ ws

  protected[this] def getStatus: ToResponseMarshallable

  protected[this] def extractClientPublicKey(ip: RemoteAddress)(route: PublicKey => Route): Route

  protected[this] def webSocketApi(ip: RemoteAddress, publicKey: PublicKey): Flow[Message, Message, NotUsed]
}
