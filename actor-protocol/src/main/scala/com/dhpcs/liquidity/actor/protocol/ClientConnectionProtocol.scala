package com.dhpcs.liquidity.actor.protocol

import com.dhpcs.liquidity.model.PublicKey
import play.api.libs.json.{Format, Json}

sealed trait ClientConnectionMessage extends Serializable

case class MessageReceivedConfirmation(deliveryId: Long) extends ClientConnectionMessage

case class ActiveClientSummary(publicKey: PublicKey) extends ClientConnectionMessage

object ClientConnectionMessage {

  implicit final val MessageReceivedConfirmationFormat: Format[MessageReceivedConfirmation] =
    Json.format[MessageReceivedConfirmation]
  implicit final val ActiveClientSummaryFormat: Format[ActiveClientSummary] = Json.format[ActiveClientSummary]

}
