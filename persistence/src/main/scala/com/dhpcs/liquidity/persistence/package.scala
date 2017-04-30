package com.dhpcs.liquidity

import akka.actor.ActorPath
import com.dhpcs.liquidity.model._

import scala.util.matching.Regex

package object persistence {

  final val ZoneIdStringPattern: Regex =
    """zone-([0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})""".r

  implicit class RichActorPath(val actorPath: ActorPath) extends AnyVal {
    def asProto: String = actorPath.toSerializationFormat
  }

  implicit class RichProtobufString(val string: String) extends AnyVal {
    def asScala: ActorPath = ActorPath.fromString(string)
  }

  implicit class RichZoneId(val zoneId: ZoneId) extends AnyVal {
    def persistenceId: String = s"zone-${zoneId.id}"
  }

  implicit class RichPublicKey(val publicKey: PublicKey) extends AnyVal {
    def persistenceId: String = s"client-${publicKey.fingerprint})"
  }

  implicit class RichZoneEvent(val event: ZoneEvent) extends AnyVal {
    def asProto: proto.persistence.ZoneEvent = proto.persistence.ZoneEvent(
      event.timestamp,
      event match {
        case ZoneCreatedEvent(_, zone) =>
          proto.persistence.ZoneEvent.Event.ZoneCreatedEvent(proto.persistence.ZoneCreatedEvent(Some(zone.asProto)))
        case ZoneJoinedEvent(_, clientConnectionActorPath, publicKey) =>
          proto.persistence.ZoneEvent.Event
            .ZoneJoinedEvent(proto.persistence.ZoneJoinedEvent(clientConnectionActorPath.asProto, publicKey.asProto))
        case ZoneQuitEvent(_, clientConnectionActorPath) =>
          proto.persistence.ZoneEvent.Event
            .ZoneQuitEvent(proto.persistence.ZoneQuitEvent(clientConnectionActorPath.asProto))
        case ZoneNameChangedEvent(_, name) =>
          proto.persistence.ZoneEvent.Event.ZoneNameChangedEvent(proto.persistence.ZoneNameChangedEvent(name))
        case MemberCreatedEvent(_, member) =>
          proto.persistence.ZoneEvent.Event
            .MemberCreatedEvent(proto.persistence.MemberCreatedEvent(Some(member.asProto)))
        case MemberUpdatedEvent(_, member) =>
          proto.persistence.ZoneEvent.Event
            .MemberUpdatedEvent(proto.persistence.MemberUpdatedEvent(Some(member.asProto)))
        case AccountCreatedEvent(_, account) =>
          proto.persistence.ZoneEvent.Event
            .AccountCreatedEvent(proto.persistence.AccountCreatedEvent(Some(account.asProto)))
        case AccountUpdatedEvent(_, account) =>
          proto.persistence.ZoneEvent.Event
            .AccountUpdatedEvent(proto.persistence.AccountUpdatedEvent(Some(account.asProto)))
        case TransactionAddedEvent(_, transaction) =>
          proto.persistence.ZoneEvent.Event
            .TransactionAddedEvent(proto.persistence.TransactionAddedEvent(Some(transaction.asProto)))
      }
    )
  }

  implicit class RichProtobufZoneEvent(val zoneEvent: proto.persistence.ZoneEvent) extends AnyVal {
    def asScala: ZoneEvent = zoneEvent.event match {
      case proto.persistence.ZoneEvent.Event.Empty =>
        throw new IllegalArgumentException("Empty Event")
      case proto.persistence.ZoneEvent.Event.ZoneCreatedEvent(value) =>
        ZoneCreatedEvent(
          zoneEvent.timestamp,
          value.zone.getOrElse(throw new IllegalArgumentException("Empty Zone")).asScala
        )
      case proto.persistence.ZoneEvent.Event.ZoneJoinedEvent(value) =>
        ZoneJoinedEvent(
          zoneEvent.timestamp,
          value.clientConnectionActorPath.asScala,
          value.publicKey.asScala
        )
      case proto.persistence.ZoneEvent.Event.ZoneQuitEvent(value) =>
        ZoneQuitEvent(
          zoneEvent.timestamp,
          value.clientConnectionActorPath.asScala
        )
      case proto.persistence.ZoneEvent.Event.ZoneNameChangedEvent(value) =>
        ZoneNameChangedEvent(
          zoneEvent.timestamp,
          value.name
        )
      case proto.persistence.ZoneEvent.Event.MemberCreatedEvent(value) =>
        MemberCreatedEvent(
          zoneEvent.timestamp,
          value.member.getOrElse(throw new IllegalArgumentException("Empty Member")).asScala
        )
      case proto.persistence.ZoneEvent.Event.MemberUpdatedEvent(value) =>
        MemberUpdatedEvent(
          zoneEvent.timestamp,
          value.member.getOrElse(throw new IllegalArgumentException("Empty Member")).asScala
        )
      case proto.persistence.ZoneEvent.Event.AccountCreatedEvent(value) =>
        AccountCreatedEvent(
          zoneEvent.timestamp,
          value.account.getOrElse(throw new IllegalArgumentException("Empty Account")).asScala
        )
      case proto.persistence.ZoneEvent.Event.AccountUpdatedEvent(value) =>
        AccountUpdatedEvent(
          zoneEvent.timestamp,
          value.account.getOrElse(throw new IllegalArgumentException("Empty Account")).asScala
        )
      case proto.persistence.ZoneEvent.Event.TransactionAddedEvent(value) =>
        TransactionAddedEvent(
          zoneEvent.timestamp,
          value.transaction.getOrElse(throw new IllegalArgumentException("Empty Transaction")).asScala
        )
    }
  }
}
