package com.dhpcs.liquidity.actor.protocol.zonemonitor

import akka.actor.typed.ActorRef
import com.dhpcs.liquidity.model._

final case class ActiveZoneSummary(
    zoneId: ZoneId,
    members: Int,
    accounts: Int,
    transactions: Int,
    metadata: Option[com.google.protobuf.struct.Struct],
    connectedClients: Set[PublicKey])

sealed abstract class ZoneMonitorMessage
case object LogActiveZonesCount extends ZoneMonitorMessage
final case class GetActiveZoneSummaries(
    replyTo: ActorRef[Set[ActiveZoneSummary]])
    extends ZoneMonitorMessage

sealed abstract class SerializableZoneMonitorMessage
    extends ZoneMonitorMessage
    with Serializable
final case class UpsertActiveZoneSummary(zoneValidator: ActorRef[Nothing],
                                         activeZoneSummary: ActiveZoneSummary)
    extends SerializableZoneMonitorMessage
