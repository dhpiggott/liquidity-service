package com.dhpcs.liquidity.actor.protocol

import com.dhpcs.liquidity.serialization.PlayJsonSerializer
import com.dhpcs.liquidity.serialization.PlayJsonSerializer._

class ZoneValidatorMessageSerializer extends PlayJsonSerializer {

  override def identifier: Int = 1668336332

  override protected val formats: Map[String, Format[_ <: AnyRef]] = Map(
    manifestToFormat[EnvelopedAuthenticatedCommandWithIds],
    manifestToFormat[AuthenticatedCommandWithIds],
    manifestToFormat[CommandReceivedConfirmation],
    manifestToFormat[ZoneAlreadyExists],
    manifestToFormat[ZoneRestarted],
    manifestToFormat[ResponseWithIds],
    manifestToFormat[NotificationWithIds],
    manifestToFormat[ActiveZoneSummary]
  )

}
