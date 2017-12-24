package com.dhpcs.liquidity.ws.protocol

import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.security.spec.X509EncodedKeySpec
import java.security.{KeyFactory, Signature}

import com.dhpcs.liquidity.proto

import scala.util.Random

object Authentication {

  private final val KeySize = 2048

  def createKeyOwnershipChallenge(): proto.ws.protocol.ClientMessage.KeyOwnershipChallenge = {
    val nonce = new Array[Byte](KeySize / 8)
    Random.nextBytes(nonce)
    proto.ws.protocol.ClientMessage.KeyOwnershipChallenge(
      com.google.protobuf.ByteString.copyFrom(nonce)
    )
  }

  def createKeyOwnershipProof(publicKey: RSAPublicKey,
                              privateKey: RSAPrivateKey,
                              keyOwnershipChallenge: proto.ws.protocol.ClientMessage.KeyOwnershipChallenge)
    : proto.ws.protocol.ServerMessage.KeyOwnershipProof = {
    def signMessage(privateKey: RSAPrivateKey)(message: Array[Byte]): Array[Byte] = {
      val s = Signature.getInstance("SHA256withRSA")
      s.initSign(privateKey)
      s.update(message)
      s.sign
    }
    val nonce = keyOwnershipChallenge.nonce.toByteArray
    proto.ws.protocol.ServerMessage.KeyOwnershipProof(
      com.google.protobuf.ByteString.copyFrom(publicKey.getEncoded),
      com.google.protobuf.ByteString.copyFrom(
        signMessage(privateKey)(nonce)
      )
    )
  }

  def isValidKeyOwnershipProof(keyOwnershipChallenge: proto.ws.protocol.ClientMessage.KeyOwnershipChallenge,
                               keyOwnershipProof: proto.ws.protocol.ServerMessage.KeyOwnershipProof): Boolean = {
    def isValidMessageSignature(publicKey: RSAPublicKey)(message: Array[Byte], signature: Array[Byte]): Boolean = {
      val s = Signature.getInstance("SHA256withRSA")
      s.initVerify(publicKey)
      s.update(message)
      s.verify(signature)
    }
    val publicKey = KeyFactory
      .getInstance("RSA")
      .generatePublic(new X509EncodedKeySpec(keyOwnershipProof.publicKey.toByteArray))
      .asInstanceOf[RSAPublicKey]
    val nonce     = keyOwnershipChallenge.nonce.toByteArray
    val signature = keyOwnershipProof.signature.toByteArray
    isValidMessageSignature(publicKey)(nonce, signature)
  }
}
