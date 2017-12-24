package com.dhpcs.liquidity.persistence.zone

import java.net.InetAddress
import java.time.Instant

import akka.typed.ActorRef
import com.dhpcs.liquidity.actor.protocol.clientconnection.SerializableClientConnectionMessage
import com.dhpcs.liquidity.model._

sealed abstract class ZoneRecord extends Serializable
final case class ZoneState(
    zone: Option[Zone],
    balances: Map[AccountId, BigDecimal],
    connectedClients: Map[ActorRef[SerializableClientConnectionMessage],
                          PublicKey])
    extends ZoneRecord
final case class ZoneEventEnvelope(remoteAddress: Option[InetAddress],
                                   publicKey: Option[PublicKey],
                                   timestamp: Instant,
                                   zoneEvent: ZoneEvent)
    extends ZoneRecord

sealed abstract class ZoneEvent
case object EmptyZoneEvent extends ZoneEvent
final case class ZoneCreatedEvent(zone: Zone) extends ZoneEvent
final case class ClientJoinedEvent(actorRefString: Option[String])
    extends ZoneEvent
final case class ClientQuitEvent(actorRefString: Option[String])
    extends ZoneEvent
final case class ZoneNameChangedEvent(name: Option[String]) extends ZoneEvent
final case class MemberCreatedEvent(member: Member) extends ZoneEvent
final case class MemberUpdatedEvent(member: Member) extends ZoneEvent
final case class AccountCreatedEvent(account: Account) extends ZoneEvent
final case class AccountUpdatedEvent(actingAs: Option[MemberId],
                                     account: Account)
    extends ZoneEvent
final case class TransactionAddedEvent(transaction: Transaction)
    extends ZoneEvent
