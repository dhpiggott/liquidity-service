package com.dhpcs.liquidity.persistence

import akka.actor.ActorPath
import com.dhpcs.liquidity.model._
import play.api.libs.json.Json

sealed trait Event {
  def timestamp: Long
}

case class ZoneCreatedEvent(timestamp: Long, zone: Zone) extends Event

object ZoneCreatedEvent {
  implicit final val ZoneCreatedEventFormat = Json.format[ZoneCreatedEvent]
}

case class ZoneJoinedEvent(timestamp: Long, clientConnectionActorPath: ActorPath, publicKey: PublicKey) extends Event

object ZoneJoinedEvent {
  implicit final val ZoneJoinedEventFormat = Json.format[ZoneJoinedEvent]
}

case class ZoneQuitEvent(timestamp: Long, clientConnectionActorPath: ActorPath) extends Event

object ZoneQuitEvent {
  implicit final val ZoneQuitEventFormat = Json.format[ZoneQuitEvent]
}

case class ZoneNameChangedEvent(timestamp: Long, name: Option[String]) extends Event

object ZoneNameChangedEvent {
  implicit final val ZoneNameChangedEventFormat = Json.format[ZoneNameChangedEvent]
}

case class MemberCreatedEvent(timestamp: Long, member: Member) extends Event

object MemberCreatedEvent {
  implicit final val MemberCreatedEventFormat = Json.format[MemberCreatedEvent]
}

case class MemberUpdatedEvent(timestamp: Long, member: Member) extends Event

object MemberUpdatedEvent {
  implicit final val MemberUpdatedEventFormat = Json.format[MemberUpdatedEvent]
}

case class AccountCreatedEvent(timestamp: Long, account: Account) extends Event

object AccountCreatedEvent {
  implicit final val AccountCreatedEventFormat = Json.format[AccountCreatedEvent]
}

case class AccountUpdatedEvent(timestamp: Long, account: Account) extends Event

object AccountUpdatedEvent {
  implicit final val AccountUpdatedEventFormat = Json.format[AccountUpdatedEvent]
}

case class TransactionAddedEvent(timestamp: Long, transaction: Transaction) extends Event

object TransactionAddedEvent {
  implicit final val TransactionAddedEventFormat = Json.format[TransactionAddedEvent]
}
