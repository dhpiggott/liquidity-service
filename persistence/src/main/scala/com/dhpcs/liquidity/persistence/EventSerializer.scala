package com.dhpcs.liquidity.persistence

import com.dhpcs.liquidity.serialization.PlayJsonSerializer
import com.dhpcs.liquidity.serialization.PlayJsonSerializer._

class EventSerializer extends PlayJsonSerializer {

  override def identifier: Int = 262953465

  override protected val formats: Map[String, Format[_ <: AnyRef]] = Map(
    manifestToFormat[ZoneCreatedEvent],
    manifestToFormat[ZoneJoinedEvent],
    manifestToFormat[ZoneQuitEvent],
    manifestToFormat[ZoneNameChangedEvent],
    manifestToFormat[MemberCreatedEvent],
    manifestToFormat[MemberUpdatedEvent],
    manifestToFormat[AccountCreatedEvent],
    manifestToFormat[AccountUpdatedEvent],
    manifestToFormat[TransactionAddedEvent]
  )

}
