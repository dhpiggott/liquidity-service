package com.dhpcs.liquidity.persistence

import akka.actor.ActorPath
import com.dhpcs.liquidity.model._
import play.api.libs.json.{Format, Json}

sealed abstract class Event extends Serializable {
  def timestamp: Long
}

case class ZoneCreatedEvent(timestamp: Long, zone: Zone)                                                extends Event
case class ZoneJoinedEvent(timestamp: Long, clientConnectionActorPath: ActorPath, publicKey: PublicKey) extends Event
case class ZoneQuitEvent(timestamp: Long, clientConnectionActorPath: ActorPath)                         extends Event
case class ZoneNameChangedEvent(timestamp: Long, name: Option[String])                                  extends Event
case class MemberCreatedEvent(timestamp: Long, member: Member)                                          extends Event
case class MemberUpdatedEvent(timestamp: Long, member: Member)                                          extends Event
case class AccountCreatedEvent(timestamp: Long, account: Account)                                       extends Event
case class AccountUpdatedEvent(timestamp: Long, account: Account)                                       extends Event
case class TransactionAddedEvent(timestamp: Long, transaction: Transaction)                             extends Event

object Event {

  implicit final val ZoneCreatedEventFormat: Format[ZoneCreatedEvent]           = Json.format[ZoneCreatedEvent]
  implicit final val ZoneJoinedEventFormat: Format[ZoneJoinedEvent]             = Json.format[ZoneJoinedEvent]
  implicit final val ZoneQuitEventFormat: Format[ZoneQuitEvent]                 = Json.format[ZoneQuitEvent]
  implicit final val ZoneNameChangedEventFormat: Format[ZoneNameChangedEvent]   = Json.format[ZoneNameChangedEvent]
  implicit final val MemberCreatedEventFormat: Format[MemberCreatedEvent]       = Json.format[MemberCreatedEvent]
  implicit final val MemberUpdatedEventFormat: Format[MemberUpdatedEvent]       = Json.format[MemberUpdatedEvent]
  implicit final val AccountCreatedEventFormat: Format[AccountCreatedEvent]     = Json.format[AccountCreatedEvent]
  implicit final val AccountUpdatedEventFormat: Format[AccountUpdatedEvent]     = Json.format[AccountUpdatedEvent]
  implicit final val TransactionAddedEventFormat: Format[TransactionAddedEvent] = Json.format[TransactionAddedEvent]

}
