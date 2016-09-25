package com.dhpcs.liquidity

import akka.actor.ActorPath
import com.dhpcs.liquidity.model.{PublicKey, ValueFormat, ZoneId}

package object persistence {
  implicit final val ActorPathFormat = ValueFormat[ActorPath, String](ActorPath.fromString, _.toSerializationFormat)

  final val ZoneIdStringPattern =
    """zone-([0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})""".r

  implicit class RichZoneId(val zoneId: ZoneId) extends AnyVal {
    def persistenceId: String = s"zone-${zoneId.id}"
  }

  implicit class RichPublicKey(val publicKey: PublicKey) extends AnyVal {
    def persistenceId: String = s"client-${publicKey.fingerprint})"
  }

}
