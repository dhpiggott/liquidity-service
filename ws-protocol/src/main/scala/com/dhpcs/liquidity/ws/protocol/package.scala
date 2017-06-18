package com.dhpcs.liquidity.ws

import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.security.spec.X509EncodedKeySpec
import java.security.{KeyFactory, Signature}

import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto

import scala.util.Random

package object protocol {

  def createKeyOwnershipChallengeMessage(): proto.ws.protocol.ClientMessage.KeyOwnershipChallenge = {
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
    val nonce = keyOwnershipChallenge.nonce.toByteArray
    proto.ws.protocol.ServerMessage.KeyOwnershipProof(
      com.google.protobuf.ByteString.copyFrom(publicKey.getEncoded),
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

  def isValidKeyOwnershipProof(keyOwnershipChallenge: proto.ws.protocol.ClientMessage.KeyOwnershipChallenge,
                               keyOwnershipProof: proto.ws.protocol.ServerMessage.KeyOwnershipProof): Boolean = {
    val publicKey = KeyFactory
      .getInstance("RSA")
      .generatePublic(new X509EncodedKeySpec(keyOwnershipProof.publicKey.toByteArray))
      .asInstanceOf[RSAPublicKey]
    val nonce     = keyOwnershipChallenge.nonce.toByteArray
    val signature = keyOwnershipProof.signature.toByteArray
    isValidMessageSignature(publicKey)(nonce, signature)
  }

  def isValidMessageSignature(publicKey: RSAPublicKey)(message: Array[Byte], signature: Array[Byte]): Boolean = {
    val s = Signature.getInstance("SHA256withRSA")
    s.initVerify(publicKey)
    s.update(message)
    s.verify(signature)
  }
}
