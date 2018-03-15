package com.dhpcs.liquidity.server.actor

import java.net.{InetAddress, InetSocketAddress}
import java.nio.channels.ServerSocketChannel
import java.security.KeyPairGenerator

import akka.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import com.dhpcs.liquidity.actor.protocol.clientmonitor._
import com.dhpcs.liquidity.model.PublicKey
import com.dhpcs.liquidity.server.actor.ClientMonitorActorSpec._
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{BeforeAndAfterAll, FreeSpec}

class ClientMonitorActorSpec
    extends FreeSpec
    with ActorTestKit
    with BeforeAndAfterAll {

  "ClientMonitorActor" - {
    "provides a summary of the active clients" in {
      val clientMonitor = spawn(ClientMonitorActor.behavior, "clientMonitor")
      val testProbe = TestProbe[Set[ActiveClientSummary]]()
      val activeClientSummary =
        ActiveClientSummary(remoteAddress, publicKey, "test-connection-id")
      clientMonitor ! UpsertActiveClientSummary(testProbe.ref,
                                                activeClientSummary)
      clientMonitor ! GetActiveClientSummaries(testProbe.ref)
      testProbe.expectMessage(Set(activeClientSummary))
    }
  }

  private[this] lazy val akkaRemotingPort = {
    val serverSocket = ServerSocketChannel.open().socket()
    serverSocket.bind(new InetSocketAddress("localhost", 0))
    val port = serverSocket.getLocalPort
    serverSocket.close()
    port
  }

  override def config: Config = ConfigFactory.parseString(s"""
       |akka {
       |  loglevel = "WARNING"
       |  actor.provider = "cluster"
       |  remote.artery {
       |    enabled = on
       |    transport = tcp
       |    canonical.hostname = "localhost"
       |    canonical.port = $akkaRemotingPort
       |  }
       |  cluster {
       |    seed-nodes = ["akka://$name@localhost:$akkaRemotingPort"]
       |    jmx.enabled = off
       |  }
       |}
     """.stripMargin)

  override protected def afterAll(): Unit =
    shutdownTestKit()

}

object ClientMonitorActorSpec {

  private val remoteAddress = InetAddress.getLoopbackAddress
  private val rsaPublicKey = {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(2048)
    val keyPair = keyPairGenerator.generateKeyPair
    keyPair.getPublic
  }
  private val publicKey = PublicKey(rsaPublicKey.getEncoded)

}
