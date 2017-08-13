package com.dhpcs.liquidity.actor.protocol.zonemonitor

import akka.actor.ActorRef
import akka.typed
import com.dhpcs.liquidity.model.{Account, Member, PublicKey, Transaction, ZoneId}

final case class ActiveZoneSummary(zoneId: ZoneId,
                                   members: Set[Member],
                                   accounts: Set[Account],
                                   transactions: Set[Transaction],
                                   metadata: Option[com.google.protobuf.struct.Struct],
                                   clientConnections: Set[PublicKey])

sealed abstract class ZoneMonitorMessage extends Serializable

final case class UpsertActiveZoneSummary(zoneValidatorActorRef: ActorRef, activeZoneSummary: ActiveZoneSummary)
    extends ZoneMonitorMessage
case object LogActiveZonesCount                                                          extends ZoneMonitorMessage
final case class GetActiveZoneSummaries(replyTo: typed.ActorRef[Set[ActiveZoneSummary]]) extends ZoneMonitorMessage
