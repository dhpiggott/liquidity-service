package com.dhpcs.liquidity.persistence

import akka.persistence.journal.WriteEventAdapter

class ZoneWriteEventAdapter extends WriteEventAdapter {

  override def manifest(event: Any): String = ""

  override def toJournal(event: Any): Any = event match {
    case zoneEvent: ZoneEvent =>
      zoneEvent.asProto
    case _ =>
      throw new IllegalArgumentException(s"Unsupported event type ${event.getClass.getName}")
  }
}
