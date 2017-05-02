package com.dhpcs.liquidity.actor.protocol

import com.dhpcs.liquidity.actor.protocol.PlayJsonSerializer._

class ClientConnectionMessageSerializer extends PlayJsonSerializer {

  override def identifier: Int = 1909424086

  override protected val formats: Map[String, Format[_ <: AnyRef]] = Map(
    manifestToFormat[MessageReceivedConfirmation],
    manifestToFormat[ActiveClientSummary]
  )

}
