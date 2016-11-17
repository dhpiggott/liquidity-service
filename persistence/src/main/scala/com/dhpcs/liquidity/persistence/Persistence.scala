package com.dhpcs.liquidity.persistence

import akka.actor.ActorPath
import com.dhpcs.liquidity.model._
import play.api.libs.json.Json

sealed trait Event {
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

  implicit final val ZoneCreatedEventFormat      = Json.format[ZoneCreatedEvent]
  implicit final val ZoneJoinedEventFormat       = Json.format[ZoneJoinedEvent]
  implicit final val ZoneQuitEventFormat         = Json.format[ZoneQuitEvent]
  implicit final val ZoneNameChangedEventFormat  = Json.format[ZoneNameChangedEvent]
  implicit final val MemberCreatedEventFormat    = Json.format[MemberCreatedEvent]
  implicit final val MemberUpdatedEventFormat    = Json.format[MemberUpdatedEvent]
  implicit final val AccountCreatedEventFormat   = Json.format[AccountCreatedEvent]
  implicit final val AccountUpdatedEventFormat   = Json.format[AccountUpdatedEvent]
  implicit final val TransactionAddedEventFormat = Json.format[TransactionAddedEvent]

}
