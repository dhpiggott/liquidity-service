package com.dhpcs.liquidity.persistence

import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.model.ProtoBindings._
import com.dhpcs.liquidity.persistence.PersistenceSpec._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import org.scalatest.FreeSpec

object PersistenceSpec {

  val zoneCreatedEvent = ZoneCreatedEvent(
    timestamp = ModelSpec.zone.created,
    zone = ModelSpec.zone
  )

  val zoneCreatedEventProto = proto.persistence.ZoneCreatedEvent(
    timestamp = ModelSpec.zoneProto.created,
    zone = Some(ModelSpec.zoneProto)
  )

}

class PersistenceSpec extends FreeSpec {

  "A ZoneCreatedEvent" - {
    s"will convert to $zoneCreatedEventProto" in assert(
      ProtoBinding[ZoneCreatedEvent, proto.persistence.ZoneCreatedEvent, Any]
        .asProto(zoneCreatedEvent) === zoneCreatedEventProto
    )
    s"will convert from $zoneCreatedEvent" in assert(
      ProtoBinding[ZoneCreatedEvent, proto.persistence.ZoneCreatedEvent, Any]
        .asScala(zoneCreatedEventProto)(()) === zoneCreatedEvent
    )
  }
}
