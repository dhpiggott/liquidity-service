package com.dhpcs.liquidity.ws

import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.security.spec.X509EncodedKeySpec
import java.security.{KeyFactory, Signature}

import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto

import scala.util.Random

package object protocol {

  def createBeginKeyOwnershipProofMessage(publicKey: RSAPublicKey): proto.ws.protocol.ServerMessage.BeginKeyOwnershipProof =
    proto.ws.protocol.ServerMessage.BeginKeyOwnershipProof(
      com.google.protobuf.ByteString.copyFrom(publicKey.getEncoded)
    )

  def createKeyOwnershipNonceMessage(): proto.ws.protocol.ClientMessage.KeyOwnershipProofNonce = {
    val nonce = new Array[Byte](KeySize / 8)
    Random.nextBytes(nonce)
    proto.ws.protocol.ClientMessage.KeyOwnershipProofNonce(
      com.google.protobuf.ByteString.copyFrom(nonce)
    )
  }

  def createCompleteKeyOwnershipProofMessage(privateKey: RSAPrivateKey,
                                             keyOwnershipProofNonceMessage: proto.ws.protocol.ClientMessage.KeyOwnershipProofNonce)
    : proto.ws.protocol.ServerMessage.CompleteKeyOwnershipProof = {
    val nonce = keyOwnershipProofNonceMessage.nonce.toByteArray
    proto.ws.protocol.ServerMessage.CompleteKeyOwnershipProof(
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

  def isValidKeyOwnershipProof(beginKeyOwnershipProof: proto.ws.protocol.ServerMessage.BeginKeyOwnershipProof,
                               keyOwnershipProofNonce: proto.ws.protocol.ClientMessage.KeyOwnershipProofNonce,
                               completeKeyOwnershipProof: proto.ws.protocol.ServerMessage.CompleteKeyOwnershipProof): Boolean = {
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
