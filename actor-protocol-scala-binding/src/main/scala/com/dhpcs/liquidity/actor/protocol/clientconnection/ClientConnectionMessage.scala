package com.dhpcs.liquidity.actor.protocol.clientconnection

import akka.actor.typed.ActorRef
import com.dhpcs.liquidity.actor.protocol.zonevalidator.ZoneValidatorMessage
import com.dhpcs.liquidity.model.ZoneId
import com.dhpcs.liquidity.ws.protocol.ZoneNotification

sealed abstract class ClientConnectionMessage
case object PublishClientStatusTick extends ClientConnectionMessage
case object ConnectionClosed extends ClientConnectionMessage
case object ZoneTerminated extends ClientConnectionMessage

sealed abstract class SerializableClientConnectionMessage
    extends ClientConnectionMessage
    with Serializable
final case class ZoneNotificationEnvelope(
    zoneValidator: ActorRef[ZoneValidatorMessage],
    zoneId: ZoneId,
    sequenceNumber: Long,
    zoneNotification: ZoneNotification)
    extends SerializableClientConnectionMessage
