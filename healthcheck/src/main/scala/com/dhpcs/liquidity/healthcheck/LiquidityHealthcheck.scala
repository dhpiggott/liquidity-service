package com.dhpcs.liquidity.healthcheck

import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.Executors

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Keep, Source}
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.testkit.TestKit
import cats.data.Validated
import com.dhpcs.liquidity.client.{LegacyServerConnection, ServerConnection}
import com.dhpcs.liquidity.healthcheck.LiquidityHealthcheck._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.ws.protocol.legacy.LegacyWsProtocol
import com.dhpcs.liquidity.ws.protocol._
import okio.ByteString
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Second, Seconds, Span}

import scala.concurrent.{ExecutionContext, Future, Promise}

object LiquidityHealthcheck {

  private final val TrustStoreFilename = "liquidity.dhpcs.com.truststore.p12"

  private final val SentinelZone = Zone(
    id = ZoneId("4cdcdb95-5647-4d46-a2f9-a68e9294d00a"),
    equityAccountId = AccountId(0.toString),
    members = Map(
      MemberId(0.toString) -> Member(
        MemberId(0.toString),
        Set(PublicKey(ByteString.decodeBase64(
          "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAtjMhJTSZ7viahRTVYYz/0mNdGXmbUQ5eNiNrLHXYkfIjSzIPGVf2a6kZCsTGISasgz8gCakyy8f2r6VL1dO/YrJpSsMmPdAoWxZ4heTh+HMeF7UARWwiMwYi7vIk4euGm3oAVaiGmMmPYfvI13mEe84ke9+YrZkG6qzqzk6YHxgwN3vaTntyAM2Fdgyl9Ki8j6lkrTf9bRzGdbZ1DNDzd9mqUJ7xVmwRMZXPNOB6D5DblLWlOYZouqGUIOjfcMkoWWrIR4dDdXzRemNPldigzW8KSEnJ0yCMX9MoNt0xk2HeP/ELqbnS7JzRiO0s61eoZbyKge25Y4jLNjBekxSrsQIDAQAB"))),
        name = Some("Bank")
      ),
      MemberId(1.toString) -> Member(
        MemberId(1.toString),
        Set(PublicKey(ByteString.decodeBase64(
          "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAtjMhJTSZ7viahRTVYYz/0mNdGXmbUQ5eNiNrLHXYkfIjSzIPGVf2a6kZCsTGISasgz8gCakyy8f2r6VL1dO/YrJpSsMmPdAoWxZ4heTh+HMeF7UARWwiMwYi7vIk4euGm3oAVaiGmMmPYfvI13mEe84ke9+YrZkG6qzqzk6YHxgwN3vaTntyAM2Fdgyl9Ki8j6lkrTf9bRzGdbZ1DNDzd9mqUJ7xVmwRMZXPNOB6D5DblLWlOYZouqGUIOjfcMkoWWrIR4dDdXzRemNPldigzW8KSEnJ0yCMX9MoNt0xk2HeP/ELqbnS7JzRiO0s61eoZbyKge25Y4jLNjBekxSrsQIDAQAB"))),
        name = Some("David")
      )
    ),
    accounts = Map(
      AccountId(0.toString) -> Account(AccountId(0.toString), ownerMemberIds = Set(MemberId(0.toString))),
      AccountId(1.toString) -> Account(AccountId(1.toString), ownerMemberIds = Set(MemberId(1.toString)))
    ),
    transactions = Map(
      TransactionId(0.toString) -> Transaction(
        TransactionId(0.toString),
        from = AccountId(0.toString),
        to = AccountId(1.toString),
        value = BigDecimal(123),
        creator = MemberId(0.toString),
        created = 1456738931256L
      ),
      TransactionId(4.toString) -> Transaction(TransactionId(4.toString),
                                               from = AccountId(0.toString),
                                               to = AccountId(1.toString),
                                               value = BigDecimal(2),
                                               creator = MemberId(0.toString),
                                               created = 1456740197666L),
      TransactionId(1.toString) -> Transaction(
        TransactionId(1.toString),
        from = AccountId(0.toString),
        to = AccountId(1.toString),
        value = BigDecimal(456),
        creator = MemberId(0.toString),
        created = 1456739064702L
      ),
      TransactionId(3.toString) -> Transaction(TransactionId(3.toString),
                                               from = AccountId(0.toString),
                                               to = AccountId(1.toString),
                                               value = BigDecimal(1),
                                               creator = MemberId(0.toString),
                                               created = 1456739925349L),
      TransactionId(2.toString) -> Transaction(
        TransactionId(2.toString),
        from = AccountId(0.toString),
        to = AccountId(1.toString),
        value = BigDecimal(789),
        creator = MemberId(0.toString),
        created = 1456739330121L
      )
    ),
    created = 1456399347712L,
    expires = 1456572147712L,
    name = Some("Test"),
    metadata = Some(
      com.google.protobuf.struct.Struct(
        Map(
          "currency" -> com.google.protobuf.struct.Value(com.google.protobuf.struct.Value.Kind.StringValue("GBP"))
        )))
  )

  def main(args: Array[String]): Unit =
    new LiquidityHealthcheck(
      scheme = args.lift(0),
      hostname = args.lift(1),
      port = args.lift(2).map(_.toInt),
      legacyHostname = args.lift(3),
      legacyPort = args.lift(4).map(_.toInt)
    ).execute(
      durations = true,
      stats = true
    )

}

class LiquidityHealthcheck(scheme: Option[String],
                           hostname: Option[String],
                           port: Option[Int],
                           legacyHostname: Option[String],
                           legacyPort: Option[Int])
    extends FreeSpec
    with ScalaFutures
    with Inside
    with BeforeAndAfterAll {

  private[this] val filesDir = Files.createTempDirectory("liquidity-healthcheck-files-dir")

  private[this] class HandlerWrapperImpl extends ServerConnection.HandlerWrapper {

    private[this] val executor = Executors.newSingleThreadExecutor()

    override def post(runnable: Runnable): Unit = {
      executor.submit(runnable); ()
    }

    override def quit(): Unit = executor.shutdown()

  }

  private[this] class LegacyHandlerWrapperImpl extends LegacyServerConnection.HandlerWrapper {

    private[this] val executor = Executors.newSingleThreadExecutor()

    override def post(runnable: Runnable): Unit = {
      executor.submit(runnable); ()
    }

    override def quit(): Unit = executor.shutdown()

  }

  private[this] object MainHandlerWrapper       extends HandlerWrapperImpl
  private[this] object LegacyMainHandlerWrapper extends LegacyHandlerWrapperImpl

  private[this] var handlerWrappers       = Seq[ServerConnection.HandlerWrapper](MainHandlerWrapper)
  private[this] var legacyHandlerWrappers = Seq[LegacyServerConnection.HandlerWrapper](LegacyMainHandlerWrapper)

  private[this] val handlerWrapperFactory = new ServerConnection.HandlerWrapperFactory {
    override def create(name: String): ServerConnection.HandlerWrapper = synchronized {
      val handlerWrapper = new HandlerWrapperImpl
      handlerWrappers = handlerWrapper +: handlerWrappers
      handlerWrapper
    }
    override def main(): ServerConnection.HandlerWrapper = MainHandlerWrapper
  }

  private[this] val legacyHandlerWrapperFactory = new LegacyServerConnection.HandlerWrapperFactory {
    override def create(name: String): LegacyServerConnection.HandlerWrapper = synchronized {
      val handlerWrapper = new LegacyHandlerWrapperImpl
      legacyHandlerWrappers = handlerWrapper +: legacyHandlerWrappers
      handlerWrapper
    }
    override def main(): LegacyServerConnection.HandlerWrapper = LegacyMainHandlerWrapper
  }

  private[this] val serverConnection = new ServerConnection(
    filesDir.toFile,
    connectivityStatePublisherBuilder = new ServerConnection.ConnectivityStatePublisherBuilder {
      override def build(serverConnection: ServerConnection): ServerConnection.ConnectivityStatePublisher =
        new ServerConnection.ConnectivityStatePublisher {
          override def isConnectionAvailable: Boolean = true
          override def register(): Unit               = ()
          override def unregister(): Unit             = ()
        }
    },
    handlerWrapperFactory,
    scheme,
    hostname,
    port
  )

  private[this] val legacyServerConnection = new LegacyServerConnection(
    filesDir.toFile,
    keyStoreInputStreamProvider = new LegacyServerConnection.KeyStoreInputStreamProvider {
      override def get(): InputStream = getClass.getClassLoader.getResourceAsStream(TrustStoreFilename)
    },
    connectivityStatePublisherBuilder = new LegacyServerConnection.ConnectivityStatePublisherBuilder {
      override def build(serverConnection: LegacyServerConnection): LegacyServerConnection.ConnectivityStatePublisher =
        new LegacyServerConnection.ConnectivityStatePublisher {
          override def isConnectionAvailable: Boolean = true
          override def register(): Unit               = ()
          override def unregister(): Unit             = ()
        }
    },
    legacyHandlerWrapperFactory,
    legacyHostname,
    legacyPort
  )

  private[this] def sendZoneCommand(zoneId: ZoneId, zoneCommand: ZoneCommand): Future[ZoneResponse] = {
    val promise = Promise[ZoneResponse]
    MainHandlerWrapper.post(
      () =>
        serverConnection
          .sendZoneCommand(
            zoneId,
            zoneCommand
          )
          .onComplete(promise.complete)(ExecutionContext.global))
    promise.future
  }

  private[this] def sendLegacyZoneCommand(command: LegacyWsProtocol.Command): Future[LegacyWsProtocol.Response] = {
    val promise = Promise[LegacyWsProtocol.Response]
    LegacyMainHandlerWrapper.post(
      () =>
        legacyServerConnection.sendCommand(
          command,
          new LegacyServerConnection.ResponseCallback {
            override def onErrorResponse(error: LegacyWsProtocol.ErrorResponse): Unit       = promise.success(error)
            override def onSuccessResponse(success: LegacyWsProtocol.SuccessResponse): Unit = promise.success(success)
          }
      ))
    promise.future
  }

  private[this] implicit val system: ActorSystem    = ActorSystem("liquidity")
  private[this] implicit val mat: ActorMaterializer = ActorMaterializer()

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    legacyHandlerWrapperFactory.synchronized(legacyHandlerWrappers.foreach(_.quit()))
    handlerWrapperFactory.synchronized(handlerWrappers.foreach(_.quit()))
    super.afterAll()
  }

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(5, Seconds)), interval = scaled(Span(1, Second)))

  private[this] def withServerConnectionStateTestProbe(
      test: TestSubscriber.Probe[ServerConnection.ConnectionState] => Unit): Unit = {
    val (queue, sub) = Source
      .queue[ServerConnection.ConnectionState](bufferSize = 0, overflowStrategy = OverflowStrategy.backpressure)
      .toMat(TestSink.probe)(Keep.both)
      .run()
    val connectionStateListener = new ServerConnection.ConnectionStateListener {
      override def onConnectionStateChanged(connectionState: ServerConnection.ConnectionState): Unit = {
        queue.offer(connectionState); ()
      }
    }
    serverConnection.registerListener(connectionStateListener)
    try test(sub)
    finally {
      serverConnection.unregisterListener(connectionStateListener)
      sub.cancel(); ()
    }
  }

  private[this] def withLegacyServerConnectionStateTestProbe(
      test: TestSubscriber.Probe[LegacyServerConnection.ConnectionState] => Unit): Unit = {
    val (queue, sub) = Source
      .queue[LegacyServerConnection.ConnectionState](bufferSize = 0, overflowStrategy = OverflowStrategy.backpressure)
      .toMat(TestSink.probe)(Keep.both)
      .run()
    val connectionStateListener = new LegacyServerConnection.ConnectionStateListener {
      override def onConnectionStateChanged(connectionState: LegacyServerConnection.ConnectionState): Unit = {
        queue.offer(connectionState); ()
      }
    }
    legacyServerConnection.registerListener(connectionStateListener)
    try test(sub)
    finally {
      legacyServerConnection.unregisterListener(connectionStateListener)
      sub.cancel(); ()
    }
  }

  "The Protobuf service" - {
    "will accept connections" in withServerConnectionStateTestProbe { sub =>
      val connectionRequestToken = new ServerConnection.ConnectionRequestToken
      sub.requestNext(ServerConnection.AVAILABLE); ()
      LegacyMainHandlerWrapper.post(() => serverConnection.requestConnection(connectionRequestToken, retry = false))
      sub
        .requestNext(ServerConnection.CONNECTING)
        .requestNext(ServerConnection.AUTHENTICATING)
        .requestNext(ServerConnection.ONLINE); ()
      LegacyMainHandlerWrapper.post(() => serverConnection.unrequestConnection(connectionRequestToken))
      sub
        .requestNext(ServerConnection.DISCONNECTING)
        .requestNext(ServerConnection.AVAILABLE); ()
    }
    s"will admit joining the sentinel zone, ${SentinelZone.id} and recover the expected state" in
      withServerConnectionStateTestProbe { sub =>
        val connectionRequestToken = new ServerConnection.ConnectionRequestToken
        sub.requestNext(ServerConnection.AVAILABLE); ()
        LegacyMainHandlerWrapper.post(() => serverConnection.requestConnection(connectionRequestToken, retry = false))
        sub
          .requestNext(ServerConnection.CONNECTING)
          .requestNext(ServerConnection.AUTHENTICATING)
          .requestNext(ServerConnection.ONLINE); ()
        inside(sendZoneCommand(SentinelZone.id, JoinZoneCommand).futureValue) {
          case JoinZoneResponse(Validated.Valid((zone, connectedClients))) =>
            assert(zone === SentinelZone)
            assert(connectedClients.values.toSet === Set(serverConnection.clientKey))
        }
        LegacyMainHandlerWrapper.post(() => serverConnection.unrequestConnection(connectionRequestToken))
        sub
          .requestNext(ServerConnection.DISCONNECTING)
          .requestNext(ServerConnection.AVAILABLE); ()
      }
  }
  "The JSON-RPC service" - {
    "will accept connections" in withLegacyServerConnectionStateTestProbe { sub =>
      val connectionRequestToken = new LegacyServerConnection.ConnectionRequestToken
      sub.requestNext(LegacyServerConnection.AVAILABLE); ()
      LegacyMainHandlerWrapper.post(() =>
        legacyServerConnection.requestConnection(connectionRequestToken, retry = false))
      sub
        .requestNext(LegacyServerConnection.CONNECTING)
        .requestNext(LegacyServerConnection.WAITING_FOR_VERSION_CHECK)
        .requestNext(LegacyServerConnection.ONLINE); ()
      LegacyMainHandlerWrapper.post(() => legacyServerConnection.unrequestConnection(connectionRequestToken))
      sub
        .requestNext(LegacyServerConnection.DISCONNECTING)
        .requestNext(LegacyServerConnection.AVAILABLE); ()
    }
    s"will admit joining the sentinel zone, ${SentinelZone.id} and recover the expected state" in withLegacyServerConnectionStateTestProbe {
      sub =>
        val connectionRequestToken = new LegacyServerConnection.ConnectionRequestToken
        sub.requestNext(LegacyServerConnection.AVAILABLE); ()
        LegacyMainHandlerWrapper.post(() =>
          legacyServerConnection.requestConnection(connectionRequestToken, retry = false))
        sub
          .requestNext(LegacyServerConnection.CONNECTING)
          .requestNext(LegacyServerConnection.WAITING_FOR_VERSION_CHECK)
          .requestNext(LegacyServerConnection.ONLINE); ()
        inside(sendLegacyZoneCommand(LegacyWsProtocol.JoinZoneCommand(SentinelZone.id)).futureValue) {
          case LegacyWsProtocol.JoinZoneResponse(zone, connectedClients) =>
            assert(zone === SentinelZone)
            assert(connectedClients === Set(legacyServerConnection.clientKey))
        }
        LegacyMainHandlerWrapper.post(() => legacyServerConnection.unrequestConnection(connectionRequestToken))
        sub
          .requestNext(LegacyServerConnection.DISCONNECTING)
          .requestNext(LegacyServerConnection.AVAILABLE); ()
    }
  }
}
