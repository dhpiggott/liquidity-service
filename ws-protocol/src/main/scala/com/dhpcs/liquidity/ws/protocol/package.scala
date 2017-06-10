package com.dhpcs.liquidity.ws

import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.security.spec.X509EncodedKeySpec
import java.security.{KeyFactory, Signature}

import com.dhpcs.liquidity.actor.protocol.ZoneValidatorMessage
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.proto.ws.protocol.KeyOwnershipProofNonce

import scala.util.Random

package object protocol {

  implicit final val PingCommandProtoBinding: ProtoBinding[PingCommand.type, com.google.protobuf.ByteString] =
    ProtoBinding.instance(
      _ => com.google.protobuf.ByteString.EMPTY,
      _ => PingCommand
    )

  implicit final val PingResponseProtoBinding: ProtoBinding[PingResponse.type, com.google.protobuf.ByteString] =
    ProtoBinding.instance(
      _ => com.google.protobuf.ByteString.EMPTY,
      _ => PingResponse
    )

  // The WS CreateZoneCommand type doesn't have a ZoneId. This is to ensure that only UUIDs generated on the _server_
  // side are used. This is where that generation happens.
  implicit final val ZoneValidatorMessageCreateZoneCommandProtoBinding
    : ProtoBinding[CreateZoneCommand, ZoneValidatorMessage.CreateZoneCommand] = ProtoBinding.instance(
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

  implicit final val QuitZoneResponseProtoBinding
    : ProtoBinding[QuitZoneResponse.type, com.google.protobuf.ByteString] = ProtoBinding.instance(
    _ => com.google.protobuf.ByteString.EMPTY,
    _ => QuitZoneResponse
  )

  implicit final val ChangeZoneNameResponseProtoBinding
    : ProtoBinding[ChangeZoneNameResponse.type, com.google.protobuf.ByteString] = ProtoBinding.instance(
    _ => com.google.protobuf.ByteString.EMPTY,
    _ => ChangeZoneNameResponse
  )

  implicit final val UpdateMemberResponseProtoBinding
    : ProtoBinding[UpdateMemberResponse.type, com.google.protobuf.ByteString] = ProtoBinding.instance(
    _ => com.google.protobuf.ByteString.EMPTY,
    _ => UpdateMemberResponse
  )

  implicit final val UpdateAccountResponseProtoBinding
    : ProtoBinding[UpdateAccountResponse.type, com.google.protobuf.ByteString] = ProtoBinding.instance(
    _ => com.google.protobuf.ByteString.EMPTY,
    _ => UpdateAccountResponse
  )

  def createBeginKeyOwnershipProofMessage(publicKey: RSAPublicKey): proto.ws.protocol.BeginKeyOwnershipProof =
    proto.ws.protocol.BeginKeyOwnershipProof(
      com.google.protobuf.ByteString.copyFrom(publicKey.getEncoded)
    )

  def createKeyOwnershipNonceMessage(): KeyOwnershipProofNonce = {
    val nonce = new Array[Byte](KeySize / 8)
    Random.nextBytes(nonce)
    proto.ws.protocol.KeyOwnershipProofNonce(
      com.google.protobuf.ByteString.copyFrom(nonce)
    )
  }

  def createCompleteKeyOwnershipProofMessage(privateKey: RSAPrivateKey,
                                             keyOwnershipProofNonceMessage: proto.ws.protocol.KeyOwnershipProofNonce)
    : proto.ws.protocol.CompleteKeyOwnershipProof = {
    val nonce = keyOwnershipProofNonceMessage.nonce.toByteArray
    proto.ws.protocol.CompleteKeyOwnershipProof(
      com.google.protobuf.ByteString.copyFrom(
        signMessage(privateKey)(nonce)
      )
    )
  }

  def signMessage(privateKey: RSAPrivateKey)(message: Array[Byte]): Array[Byte] = {
    val s = Signature.getInstance("SHA256withRSA")
    s.initSign(privateKey)
    s.update(message)
    s.sign
  }

  def isValidKeyOwnershipProof(beginKeyOwnershipProof: proto.ws.protocol.BeginKeyOwnershipProof,
                               keyOwnershipProofNonce: proto.ws.protocol.KeyOwnershipProofNonce,
                               completeKeyOwnershipProof: proto.ws.protocol.CompleteKeyOwnershipProof): Boolean = {
    val publicKey = KeyFactory
      .getInstance("RSA")
      .generatePublic(new X509EncodedKeySpec(beginKeyOwnershipProof.publicKey.toByteArray))
      .asInstanceOf[RSAPublicKey]
    val nonce     = keyOwnershipProofNonce.nonce.toByteArray
    val signature = completeKeyOwnershipProof.signature.toByteArray
    isValidMessageSignature(publicKey)(nonce, signature)
  }

  def isValidMessageSignature(publicKey: RSAPublicKey)(message: Array[Byte], signature: Array[Byte]): Boolean = {
    val s = Signature.getInstance("SHA256withRSA")
    s.initVerify(publicKey)
    s.update(message)
    s.verify(signature)
  }
}
