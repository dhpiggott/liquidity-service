package com.dhpcs.liquidity.server

import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.security.{KeyPairGenerator, Signature}

import com.dhpcs.liquidity.proto

object TestKit {

  def freePort(): Int = {
    val serverSocket = ServerSocketChannel.open().socket()
    serverSocket.bind(new InetSocketAddress("localhost", 0))
    val port = serverSocket.getLocalPort
    serverSocket.close()
    port
  }

  val (rsaPrivateKey: RSAPrivateKey, rsaPublicKey: RSAPublicKey) = {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(2048)
    val keyPair = keyPairGenerator.generateKeyPair
    (keyPair.getPrivate, keyPair.getPublic)
  }

  def createKeyOwnershipProof(
      publicKey: RSAPublicKey,
      privateKey: RSAPrivateKey,
      keyOwnershipChallenge: proto.ws.protocol.ClientMessage.KeyOwnershipChallenge)
    : proto.ws.protocol.ServerMessage.KeyOwnershipProof = {
    def signMessage(privateKey: RSAPrivateKey)(
        message: Array[Byte]): Array[Byte] = {
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
}
