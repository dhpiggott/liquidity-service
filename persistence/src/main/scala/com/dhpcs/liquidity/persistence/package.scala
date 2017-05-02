package com.dhpcs.liquidity

import akka.actor.ActorPath
import com.dhpcs.liquidity.model._

import scala.util.matching.Regex

package object persistence {

  final val ZoneIdStringPattern: Regex =
    """zone-([0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})""".r

  implicit class RichZoneId(val zoneId: ZoneId) extends AnyVal {
    def persistenceId: String = s"zone-${zoneId.id}"
  }

  implicit class RichPublicKey(val publicKey: PublicKey) extends AnyVal {
    def persistenceId: String = s"client-${publicKey.fingerprint})"
  }

  implicit final val ActorPathProtoConverter: ProtoConverter[ActorPath, String] =
    ProtoConverter.instance(_.toSerializationFormat, ActorPath.fromString)

  implicit final val ZoneEventProtoConverter: ProtoConverter[ZoneEvent, proto.persistence.ZoneEvent] =
    ProtoConverter.instance(
      zoneEvent =>
        proto.persistence.ZoneEvent(
          timestamp = zoneEvent.timestamp,
          event = zoneEvent match {
            case ZoneCreatedEvent(_, zone) =>
              proto.persistence.ZoneEvent.Event.ZoneCreatedEvent(
                proto.persistence.ZoneCreatedEvent(
                  zone = Some(
                    ProtoConverter[Zone, proto.model.Zone].asProto(zone)
                  )
                ))
            case ZoneJoinedEvent(_, clientConnectionActorPath, publicKey) =>
              proto.persistence.ZoneEvent.Event.ZoneJoinedEvent(proto.persistence.ZoneJoinedEvent(
                clientConnectionActorPath = ProtoConverter[ActorPath, String].asProto(clientConnectionActorPath),
                publicKey = ProtoConverter[PublicKey, com.google.protobuf.ByteString].asProto(publicKey)
              ))
            case ZoneQuitEvent(_, clientConnectionActorPath) =>
              proto.persistence.ZoneEvent.Event.ZoneQuitEvent(
                proto.persistence.ZoneQuitEvent(
                  clientConnectionActorPath = ProtoConverter[ActorPath, String].asProto(clientConnectionActorPath)
                ))
            case ZoneNameChangedEvent(_, name) =>
              proto.persistence.ZoneEvent.Event.ZoneNameChangedEvent(proto.persistence.ZoneNameChangedEvent(name))
            case MemberCreatedEvent(_, member) =>
              proto.persistence.ZoneEvent.Event.MemberCreatedEvent(
                proto.persistence.MemberCreatedEvent(
                  member = Some(
                    ProtoConverter[Member, proto.model.Member].asProto(member)
                  )
                ))
            case MemberUpdatedEvent(_, member) =>
              proto.persistence.ZoneEvent.Event.MemberUpdatedEvent(
                proto.persistence.MemberUpdatedEvent(
                  member = Some(
                    ProtoConverter[Member, proto.model.Member].asProto(member)
                  )
                ))
            case AccountCreatedEvent(_, account) =>
              proto.persistence.ZoneEvent.Event.AccountCreatedEvent(
                proto.persistence.AccountCreatedEvent(
                  account = Some(
                    ProtoConverter[Account, proto.model.Account].asProto(account)
                  )
                ))
            case AccountUpdatedEvent(_, account) =>
              proto.persistence.ZoneEvent.Event.AccountUpdatedEvent(
                proto.persistence.AccountUpdatedEvent(
                  account = Some(
                    ProtoConverter[Account, proto.model.Account].asProto(account)
                  )
                ))
            case TransactionAddedEvent(_, transaction) =>
              proto.persistence.ZoneEvent.Event.TransactionAddedEvent(
                proto.persistence.TransactionAddedEvent(
                  transaction = Some(
                    ProtoConverter[Transaction, proto.model.Transaction].asProto(transaction)
                  )
                ))
          }
      ),
      zoneEvent =>
        zoneEvent.event match {
          case proto.persistence.ZoneEvent.Event.Empty =>
            throw new IllegalArgumentException("Empty Event")
          case proto.persistence.ZoneEvent.Event.ZoneCreatedEvent(value) =>
            ZoneCreatedEvent(
              zoneEvent.timestamp,
              ProtoConverter[Zone, proto.model.Zone]
                .asScala(value.zone.getOrElse(throw new IllegalArgumentException("Empty Zone")))
            )
          case proto.persistence.ZoneEvent.Event.ZoneJoinedEvent(value) =>
            ZoneJoinedEvent(
              zoneEvent.timestamp,
              ActorPathProtoConverter.asScala(value.clientConnectionActorPath),
              PublicKeyProtoConverter.asScala(value.publicKey)
            )
          case proto.persistence.ZoneEvent.Event.ZoneQuitEvent(value) =>
            ZoneQuitEvent(
              zoneEvent.timestamp,
              ActorPathProtoConverter.asScala(value.clientConnectionActorPath)
            )
          case proto.persistence.ZoneEvent.Event.ZoneNameChangedEvent(value) =>
            ZoneNameChangedEvent(
              zoneEvent.timestamp,
              value.name
            )
          case proto.persistence.ZoneEvent.Event.MemberCreatedEvent(value) =>
            MemberCreatedEvent(
              zoneEvent.timestamp,
              ProtoConverter[Member, proto.model.Member]
                .asScala(value.member.getOrElse(throw new IllegalArgumentException("Empty Member")))
            )
          case proto.persistence.ZoneEvent.Event.MemberUpdatedEvent(value) =>
            MemberUpdatedEvent(
              zoneEvent.timestamp,
              ProtoConverter[Member, proto.model.Member]
                .asScala(value.member.getOrElse(throw new IllegalArgumentException("Empty Member")))
            )
          case proto.persistence.ZoneEvent.Event.AccountCreatedEvent(value) =>
            AccountCreatedEvent(
              zoneEvent.timestamp,
              ProtoConverter[Account, proto.model.Account]
                .asScala(value.account.getOrElse(throw new IllegalArgumentException("Empty Account")))
            )
          case proto.persistence.ZoneEvent.Event.AccountUpdatedEvent(value) =>
            AccountUpdatedEvent(
              zoneEvent.timestamp,
              ProtoConverter[Account, proto.model.Account]
                .asScala(value.account.getOrElse(throw new IllegalArgumentException("Empty Account")))
            )
          case proto.persistence.ZoneEvent.Event.TransactionAddedEvent(value) =>
            TransactionAddedEvent(
              zoneEvent.timestamp,
              ProtoConverter[Transaction, proto.model.Transaction]
                .asScala(value.transaction.getOrElse(throw new IllegalArgumentException("Empty Transaction")))
            )
      }
    )
}
