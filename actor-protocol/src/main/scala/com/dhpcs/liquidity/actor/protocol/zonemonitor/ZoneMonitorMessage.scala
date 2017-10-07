package com.dhpcs.liquidity.actor.protocol.zonemonitor

import akka.typed
import com.dhpcs.liquidity.actor.protocol.zonevalidator.ZoneValidatorMessage
import com.dhpcs.liquidity.model._

final case class ActiveZoneSummary(zoneId: ZoneId,
                                   members: Set[Member],
                                   accounts: Set[Account],
                                   transactions: Set[Transaction],
                                   metadata: Option[com.google.protobuf.struct.Struct],
                                   clientConnections: Set[PublicKey])

sealed abstract class ZoneMonitorMessage
case object LogActiveZonesCount                                                          extends ZoneMonitorMessage
final case class GetActiveZoneSummaries(replyTo: typed.ActorRef[Set[ActiveZoneSummary]]) extends ZoneMonitorMessage

sealed abstract class SerializableZoneMonitorMessage extends ZoneMonitorMessage with Serializable
final case class UpsertActiveZoneSummary(zoneValidator: typed.ActorRef[ZoneValidatorMessage],
                                         activeZoneSummary: ActiveZoneSummary)
    extends SerializableZoneMonitorMessage
