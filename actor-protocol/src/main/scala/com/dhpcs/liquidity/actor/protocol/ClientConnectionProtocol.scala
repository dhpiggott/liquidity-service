package com.dhpcs.liquidity.actor.protocol

import com.dhpcs.liquidity.model.PublicKey

sealed abstract class ClientConnectionMessage extends Serializable

final case class MessageReceivedConfirmation(deliveryId: Long) extends ClientConnectionMessage

final case class ActiveClientSummary(publicKey: PublicKey) extends ClientConnectionMessage
