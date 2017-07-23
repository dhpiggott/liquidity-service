package com.dhpcs.liquidity.persistence

import akka.actor.ActorPath
import com.dhpcs.liquidity.model._

sealed abstract class ZoneValidatorRecord extends Serializable

final case class ZoneSnapshot(zoneState: ZoneState) extends ZoneValidatorRecord

// TODO: Create and migrate to envelope
sealed abstract class ZoneEvent extends ZoneValidatorRecord {
  def timestamp: Long
}

final case class ZoneCreatedEvent(timestamp: Long, zone: Zone) extends ZoneEvent
// TODO: Delete or rename as Client{Joined,Quit}Event, add metadata
final case class ZoneJoinedEvent(timestamp: Long, clientConnectionActorPath: ActorPath, publicKey: PublicKey)
    extends ZoneEvent
final case class ZoneQuitEvent(timestamp: Long, clientConnectionActorPath: ActorPath) extends ZoneEvent
final case class ZoneNameChangedEvent(timestamp: Long, name: Option[String])          extends ZoneEvent
final case class MemberCreatedEvent(timestamp: Long, member: Member)                  extends ZoneEvent
final case class MemberUpdatedEvent(timestamp: Long, member: Member)                  extends ZoneEvent
final case class AccountCreatedEvent(timestamp: Long, account: Account)               extends ZoneEvent
final case class AccountUpdatedEvent(timestamp: Long, account: Account)               extends ZoneEvent
final case class TransactionAddedEvent(timestamp: Long, transaction: Transaction)     extends ZoneEvent
