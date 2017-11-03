package com.dhpcs.liquidity.server

import akka.persistence.journal.{Tagged, WriteEventAdapter}
import com.dhpcs.liquidity.persistence.EventTags

class ZoneEventAdapter extends WriteEventAdapter {

  override def toJournal(event: Any): Any =
    Tagged(event, Set(EventTags.ZoneEventTag))

  override def manifest(event: Any): String = ""

}
