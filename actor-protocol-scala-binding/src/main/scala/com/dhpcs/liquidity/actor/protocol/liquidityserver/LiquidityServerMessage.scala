package com.dhpcs.liquidity.actor.protocol.liquidityserver

import com.dhpcs.liquidity.ws.protocol.ZoneResponse

sealed abstract class LiquidityServerMessage

sealed abstract class SerializableLiquidityServerMessage
    extends LiquidityServerMessage
    with Serializable
final case class ZoneResponseEnvelope(zoneResponse: ZoneResponse)
    extends LiquidityServerMessage
