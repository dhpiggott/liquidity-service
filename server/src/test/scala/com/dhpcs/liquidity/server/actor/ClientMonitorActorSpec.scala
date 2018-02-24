package com.dhpcs.liquidity.server.actor

import java.net.InetSocketAddress
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

  "A ClientMonitorActor" - {
    "provides a summary of the active clients" in {
      val clientMonitor = spawn(ClientMonitorActor.behavior, "clientMonitor")
      val testProbe = TestProbe[Set[ActiveClientSummary]]()
      val activeClientSummary =
        ActiveClientSummary(PublicKey(rsaPublicKey.getEncoded))
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
       |  remote.netty.tcp {
       |    hostname = "localhost"
       |    port = $akkaRemotingPort
       |  }
       |  cluster {
       |    metrics.enabled = off
       |    seed-nodes = ["akka.tcp://$name@localhost:$akkaRemotingPort"]
       |    jmx.multi-mbeans-in-same-jvm = on
       |  }
       |}
     """.stripMargin)

  override protected def afterAll(): Unit =
    shutdownTestKit()

}

object ClientMonitorActorSpec {

  private val rsaPublicKey = {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(2048)
    val keyPair = keyPairGenerator.generateKeyPair
    keyPair.getPublic
  }
}
