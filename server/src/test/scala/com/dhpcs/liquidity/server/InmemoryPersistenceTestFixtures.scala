package com.dhpcs.liquidity.server

import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.security.{KeyPairGenerator, Signature}

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.dhpcs.liquidity.proto
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Suite}

trait InmemoryPersistenceTestFixtures extends BeforeAndAfterAll { this: Suite =>

  protected[this] val (rsaPrivateKey: RSAPrivateKey,
                       rsaPublicKey: RSAPublicKey) = {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(2048)
    val keyPair = keyPairGenerator.generateKeyPair
    (keyPair.getPrivate, keyPair.getPublic)
  }

  protected[this] def createKeyOwnershipProof(
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

  private[this] val akkaRemotingPort = {
    val serverSocket = ServerSocketChannel.open().socket()
    serverSocket.bind(new InetSocketAddress("localhost", 0))
    val port = serverSocket.getLocalPort
    serverSocket.close()
    port
  }
  private[this] val config = ConfigFactory.parseString(s"""
       |akka {
       |  loglevel = "WARNING"
       |  actor {
       |    provider = "akka.cluster.ClusterActorRefProvider"
       |    serializers {
       |      zone-record = "com.dhpcs.liquidity.server.serialization.ZoneRecordSerializer"
       |    }
       |    serialization-bindings {
       |      "com.dhpcs.liquidity.persistence.zone.ZoneRecord" = zone-record
       |    }
       |    allow-java-serialization = off
       |  }
       |  remote.netty.tcp {
       |    hostname = "localhost"
       |    port = $akkaRemotingPort
       |  }
       |  cluster {
       |    metrics.enabled = off
       |    seed-nodes = ["akka.tcp://liquidity@localhost:$akkaRemotingPort"]
       |    jmx.multi-mbeans-in-same-jvm = on
       |  }
       |  persistence {
       |    journal.plugin = "inmemory-journal"
       |    snapshot-store.plugin = "inmemory-snapshot-store"
       |  }
       |}
     """.stripMargin)

  protected[this] implicit val system: ActorSystem =
    ActorSystem("liquidity", config)

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }
}
