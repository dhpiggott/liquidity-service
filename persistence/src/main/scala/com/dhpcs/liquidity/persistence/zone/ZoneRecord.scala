package com.dhpcs.liquidity.persistence.zone

import java.net.InetAddress
import java.time.Instant

import akka.typed.ActorRef
import com.dhpcs.liquidity.model._

sealed abstract class ZoneRecord                    extends Serializable
final case class ZoneSnapshot(zoneState: ZoneState) extends ZoneRecord
final case class ZoneEventEnvelope(remoteAddress: Option[InetAddress],
                                   publicKey: Option[PublicKey],
                                   timestamp: Instant,
                                   zoneEvent: ZoneEvent)
    extends ZoneRecord

sealed abstract class ZoneEvent
case object EmptyZoneEvent                    extends ZoneEvent
final case class ZoneCreatedEvent(zone: Zone) extends ZoneEvent
// TODO: Not Any!
final case class ClientJoinedEvent(clientConnectionActorRef: Option[ActorRef[Any]]) extends ZoneEvent
final case class ClientQuitEvent(clientConnectionActorRef: Option[ActorRef[Any]])   extends ZoneEvent
final case class ZoneNameChangedEvent(name: Option[String])                         extends ZoneEvent
final case class MemberCreatedEvent(member: Member)                                 extends ZoneEvent
final case class MemberUpdatedEvent(member: Member)                                 extends ZoneEvent
final case class AccountCreatedEvent(account: Account)                              extends ZoneEvent
final case class AccountUpdatedEvent(actingAs: Option[MemberId], account: Account)  extends ZoneEvent
final case class TransactionAddedEvent(transaction: Transaction)                    extends ZoneEvent
