package com.dhpcs.liquidity.persistence

import akka.persistence.journal.{EventAdapter, EventSeq}
import com.dhpcs.liquidity.model.ProtoConverter
import com.dhpcs.liquidity.proto

class ZoneEventAdapter extends EventAdapter {

  override def manifest(event: Any): String = ""

  override def toJournal(event: Any): Any = event match {
    case zoneEvent: ZoneEvent =>
      ProtoConverter[ZoneEvent, proto.persistence.ZoneEvent].asProto(zoneEvent)
    case _ =>
      throw new IllegalArgumentException(s"Unsupported event type ${event.getClass.getName}")
  }

  override def fromJournal(event: Any, manifest: String): EventSeq = event match {
    case zoneEvent: proto.persistence.ZoneEvent =>
      EventSeq.single(ProtoConverter[ZoneEvent, proto.persistence.ZoneEvent].asScala(zoneEvent))
    case _ =>
      throw new IllegalArgumentException(s"Unsupported event type ${event.getClass.getName}")
  }
}
