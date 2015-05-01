package com.dhpcs.liquidity.models

import play.api.libs.json.Json

case class PostedCommand(connectionNumber: Int, command: Command)

object PostedCommand {

  implicit val postedCommandFormat = Json.format[PostedCommand]

}