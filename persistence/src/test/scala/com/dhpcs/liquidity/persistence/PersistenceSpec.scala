package com.dhpcs.liquidity.persistence

import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.persistence.PersistenceSpec._
import com.dhpcs.liquidity.proto
import org.scalatest.FreeSpec

object PersistenceSpec {

  val zoneCreatedEvent = ZoneCreatedEvent(
    timestamp = ModelSpec.zone.created,
    zone = ModelSpec.zone
  )

  val zoneCreatedEventProto = proto.persistence.ZoneEvent(
    timestamp = ModelSpec.zoneProto.created,
    event = proto.persistence.ZoneEvent.Event.ZoneCreatedEvent(
      proto.persistence.ZoneCreatedEvent(
        zone = Some(ModelSpec.zoneProto)
      ))
  )

}

class PersistenceSpec extends FreeSpec {

  "A ZoneCreatedEvent" - {
    s"will convert to $zoneCreatedEventProto" in assert(
      ProtoConverter[ZoneEvent, proto.persistence.ZoneEvent].asProto(zoneCreatedEvent) === zoneCreatedEventProto
    )
    s"will convert from $zoneCreatedEvent" in assert(
      ProtoConverter[ZoneEvent, proto.persistence.ZoneEvent].asScala(zoneCreatedEventProto) === zoneCreatedEvent
    )
  }
}
