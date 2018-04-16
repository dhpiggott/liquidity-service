package com.dhpcs.liquidity.actor.protocol.zonemonitor

import akka.actor.typed.ActorRef
import com.dhpcs.liquidity.actor.protocol.clientconnection._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.persistence.zone._

final case class ActiveZoneSummary(
    zoneId: ZoneId,
    members: Int,
    accounts: Int,
    transactions: Int,
    metadata: Option[com.google.protobuf.struct.Struct],
    connectedClients: Map[ActorRef[ZoneNotificationEnvelope], ConnectedClient])

sealed abstract class ZoneMonitorMessage
case object LogActiveZonesCount extends ZoneMonitorMessage
final case class GetActiveZoneSummaries(
    replyTo: ActorRef[Set[ActiveZoneSummary]])
    extends ZoneMonitorMessage
final case class DeleteActiveZoneSummary(zoneValidator: ActorRef[Nothing])
    extends ZoneMonitorMessage

sealed abstract class SerializableZoneMonitorMessage
    extends ZoneMonitorMessage
    with Serializable
final case class UpsertActiveZoneSummary(zoneValidator: ActorRef[Nothing],
                                         activeZoneSummary: ActiveZoneSummary)
    extends SerializableZoneMonitorMessage
