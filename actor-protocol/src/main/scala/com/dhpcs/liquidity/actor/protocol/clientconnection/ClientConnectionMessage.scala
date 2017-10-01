package com.dhpcs.liquidity.actor.protocol.clientconnection

import com.dhpcs.liquidity.model.ZoneId
import com.dhpcs.liquidity.ws.protocol.{ZoneNotification, ZoneResponse}

sealed abstract class ClientConnectionMessage extends Serializable

final case class ZoneResponseEnvelope(correlationId: Long, zoneResponse: ZoneResponse) extends ClientConnectionMessage
final case class ZoneNotificationEnvelope(zoneId: ZoneId, sequenceNumber: Long, zoneNotification: ZoneNotification)
    extends ClientConnectionMessage
