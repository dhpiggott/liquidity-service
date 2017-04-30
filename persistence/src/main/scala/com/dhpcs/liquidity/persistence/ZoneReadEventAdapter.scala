package com.dhpcs.liquidity.persistence

import akka.persistence.journal.{EventSeq, ReadEventAdapter}
import com.dhpcs.liquidity.proto

class ZoneReadEventAdapter extends ReadEventAdapter {

  override def fromJournal(event: Any, manifest: String): EventSeq = event match {
    case zoneEvent: proto.persistence.ZoneEvent =>
      EventSeq.single(zoneEvent.asScala)
    case _ =>
      throw new IllegalArgumentException(s"Unsupported event type ${event.getClass.getName}")
  }
}
