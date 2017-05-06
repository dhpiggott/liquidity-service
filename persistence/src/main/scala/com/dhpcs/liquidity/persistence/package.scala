package com.dhpcs.liquidity

import akka.actor.ActorPath
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.serialization.ProtoConverter

import scala.reflect.ClassTag
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

  def optionProtoConverter[S, P](
      implicit protoConverter: ProtoConverter[S, P],
      protoClassTag: ClassTag[P]
  ): ProtoConverter[S, Option[P]] =
    ProtoConverter.instance(
      s => Some(protoConverter.asProto(s)),
      p =>
        protoConverter.asScala(
          p.getOrElse(throw new IllegalArgumentException(s"Empty ${protoClassTag.runtimeClass.getSimpleName}"))
      )
    )

  implicit final val ZoneOptionProtoConverter: ProtoConverter[Zone, Option[proto.model.Zone]] =
    optionProtoConverter[Zone, proto.model.Zone]

  implicit final val MemberOptionProtoConverter: ProtoConverter[Member, Option[proto.model.Member]] =
    optionProtoConverter[Member, proto.model.Member]

  implicit final val AccountOptionProtoConverter: ProtoConverter[Account, Option[proto.model.Account]] =
    optionProtoConverter[Account, proto.model.Account]

  implicit final val TransactionOptionProtoConverter: ProtoConverter[Transaction, Option[proto.model.Transaction]] =
    optionProtoConverter[Transaction, proto.model.Transaction]

}
