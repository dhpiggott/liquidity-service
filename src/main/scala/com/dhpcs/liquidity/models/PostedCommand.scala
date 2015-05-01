package com.dhpcs.liquidity.models

import play.api.libs.json.Json

// TODO
case class PostedCommand(connectionNumber: Int, inboundMessage: Command)

object PostedCommand {

  implicit val postedCommandFormat = Json.format[PostedCommand]

}