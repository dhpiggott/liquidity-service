package com.dhpcs.liquidity.ws

import com.dhpcs.liquidity.actor.protocol.ZoneValidatorMessage
import com.dhpcs.liquidity.model.ZoneId
import com.dhpcs.liquidity.serialization.ProtoConverter

package object protocol {

  // The WS CreateZoneCommand type doesn't have a ZoneId. This is to ensure that only UUIDs generated on the _server_
  // side are used. This is where that generation happens.
  implicit final val ZoneValidatorMessageCreateZoneCommandProtoConverter
    : ProtoConverter[CreateZoneCommand, ZoneValidatorMessage.CreateZoneCommand] = ProtoConverter.instance(
    createZoneCommand =>
      ZoneValidatorMessage.CreateZoneCommand(
        zoneId = ZoneId.generate,
        createZoneCommand.equityOwnerPublicKey,
        createZoneCommand.equityOwnerName,
        createZoneCommand.equityOwnerMetadata,
        createZoneCommand.equityAccountName,
        createZoneCommand.equityAccountMetadata,
        createZoneCommand.name,
        createZoneCommand.metadata
    ),
    createZoneCommand =>
      CreateZoneCommand(
        createZoneCommand.equityOwnerPublicKey,
        createZoneCommand.equityOwnerName,
        createZoneCommand.equityOwnerMetadata,
        createZoneCommand.equityAccountName,
        createZoneCommand.equityAccountMetadata,
        createZoneCommand.name,
        createZoneCommand.metadata
    )
  )

}
