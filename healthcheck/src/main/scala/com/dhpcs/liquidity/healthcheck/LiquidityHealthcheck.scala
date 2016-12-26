package com.dhpcs.liquidity.healthcheck

import java.io.InputStream
import java.nio.file.Files
import java.security.Security
import java.util.UUID
import java.util.concurrent.Executors

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Keep, Source}
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.testkit.TestKit
import com.dhpcs.jsonrpc.ResponseCompanion.ErrorResponse
import com.dhpcs.liquidity.client.ServerConnection
import com.dhpcs.liquidity.client.ServerConnection._
import com.dhpcs.liquidity.healthcheck.LiquidityHealthcheck._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.protocol._
import okio.ByteString
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Second, Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, Inside, Matchers, fixture}
import org.spongycastle.jce.provider.BouncyCastleProvider
import play.api.libs.json.Json

import scala.concurrent.{Future, Promise}

object LiquidityHealthcheck {

  private final val TrustStoreFilename = "liquidity.dhpcs.com.truststore.p12"

  private final val SentinelZone = Zone(
    id = ZoneId(UUID.fromString("4cdcdb95-5647-4d46-a2f9-a68e9294d00a")),
    equityAccountId = AccountId(0),
    members = Map(
      MemberId(0) -> Member(
        MemberId(0),
        PublicKey(ByteString.decodeBase64(
          "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAtjMhJTSZ7viahRTVYYz/0mNdGXmbUQ5eNiNrLHXYkfIjSzIPGVf2a6kZCsTGISasgz8gCakyy8f2r6VL1dO/YrJpSsMmPdAoWxZ4heTh+HMeF7UARWwiMwYi7vIk4euGm3oAVaiGmMmPYfvI13mEe84ke9+YrZkG6qzqzk6YHxgwN3vaTntyAM2Fdgyl9Ki8j6lkrTf9bRzGdbZ1DNDzd9mqUJ7xVmwRMZXPNOB6D5DblLWlOYZouqGUIOjfcMkoWWrIR4dDdXzRemNPldigzW8KSEnJ0yCMX9MoNt0xk2HeP/ELqbnS7JzRiO0s61eoZbyKge25Y4jLNjBekxSrsQIDAQAB")),
        name = Some("Bank")
      ),
      MemberId(1) -> Member(
        MemberId(1),
        PublicKey(ByteString.decodeBase64(
          "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAtjMhJTSZ7viahRTVYYz/0mNdGXmbUQ5eNiNrLHXYkfIjSzIPGVf2a6kZCsTGISasgz8gCakyy8f2r6VL1dO/YrJpSsMmPdAoWxZ4heTh+HMeF7UARWwiMwYi7vIk4euGm3oAVaiGmMmPYfvI13mEe84ke9+YrZkG6qzqzk6YHxgwN3vaTntyAM2Fdgyl9Ki8j6lkrTf9bRzGdbZ1DNDzd9mqUJ7xVmwRMZXPNOB6D5DblLWlOYZouqGUIOjfcMkoWWrIR4dDdXzRemNPldigzW8KSEnJ0yCMX9MoNt0xk2HeP/ELqbnS7JzRiO0s61eoZbyKge25Y4jLNjBekxSrsQIDAQAB")),
        name = Some("David")
      )
    ),
    accounts = Map(
      AccountId(0) -> Account(AccountId(0), ownerMemberIds = Set(MemberId(0))),
      AccountId(1) -> Account(AccountId(1), ownerMemberIds = Set(MemberId(1)))
    ),
    transactions = Map(
      TransactionId(0) -> Transaction(TransactionId(0),
                                      from = AccountId(0),
                                      to = AccountId(1),
                                      value = BigDecimal(123),
                                      creator = MemberId(0),
                                      created = 1456738931256L),
      TransactionId(4) -> Transaction(TransactionId(4),
                                      from = AccountId(0),
                                      to = AccountId(1),
                                      value = BigDecimal(2),
                                      creator = MemberId(0),
                                      created = 1456740197666L),
      TransactionId(1) -> Transaction(TransactionId(1),
                                      from = AccountId(0),
                                      to = AccountId(1),
                                      value = BigDecimal(456),
                                      creator = MemberId(0),
                                      created = 1456739064702L),
      TransactionId(3) -> Transaction(TransactionId(3),
                                      from = AccountId(0),
                                      to = AccountId(1),
                                      value = BigDecimal(1),
                                      creator = MemberId(0),
                                      created = 1456739925349L),
      TransactionId(2) -> Transaction(TransactionId(2),
                                      from = AccountId(0),
                                      to = AccountId(1),
                                      value = BigDecimal(789),
                                      creator = MemberId(0),
                                      created = 1456739330121L)
    ),
    created = 1456399347712L,
    expires = 1456572147712L,
    name = Some("Test"),
    metadata = Some(Json.obj("currency" -> "GBP"))
  )

  def main(args: Array[String]): Unit =
    (new LiquidityHealthcheck).execute(
      durations = true,
      stats = true
    )

}

class LiquidityHealthcheck
    extends fixture.WordSpec
    with Matchers
    with ScalaFutures
    with Inside
    with BeforeAndAfterAll {

  private[this] val prngFixesApplicator = new PRNGFixesApplicator {
    override def apply(): Unit = ()
  }

  private[this] val filesDirDirectory = {
    val filesDirDirectory = Files.createTempDirectory("liquidity-files-dir").toFile
    filesDirDirectory.deleteOnExit()
    filesDirDirectory
  }

  private[this] val keyStoreInputStreamProvider = new KeyStoreInputStreamProvider {
    override def get(): InputStream = getClass.getClassLoader.getResourceAsStream(TrustStoreFilename)
  }

  private[this] val connectivityStatePublisherBuilder = new ConnectivityStatePublisherBuilder {
    override def build(serverConnection: ServerConnection): ConnectivityStatePublisher =
      new ConnectivityStatePublisher {
        override def isConnectionAvailable: Boolean = true
        override def register(): Unit               = ()
        override def unregister(): Unit             = ()
      }
  }

  private[this] class HandlerWrapperImpl extends HandlerWrapper {

    private[this] val executor = Executors.newSingleThreadExecutor()

    override def post(runnable: Runnable): Unit = executor.submit(runnable)
    override def quit(): Unit                   = executor.shutdown()

  }

  private[this] object MainHandlerWrapper extends HandlerWrapperImpl

  private[this] var handlerWrappers = Seq[HandlerWrapper](MainHandlerWrapper)

  private[this] val handlerWrapperFactory = new HandlerWrapperFactory {
    override def create(name: String): HandlerWrapper = synchronized {
      val handlerWrapper = new HandlerWrapperImpl
      handlerWrappers = handlerWrapper +: handlerWrappers
      handlerWrapper
    }
    override def main(): HandlerWrapper = MainHandlerWrapper
  }

  private[this] val serverConnection = ServerConnection.getInstance(
    prngFixesApplicator,
    filesDirDirectory,
    keyStoreInputStreamProvider,
    connectivityStatePublisherBuilder,
    handlerWrapperFactory
  )

  private[this] def send(command: Command): Future[Either[ErrorResponse, ResultResponse]] = {
    val promise = Promise[Either[ErrorResponse, ResultResponse]]
    MainHandlerWrapper.post(
      serverConnection.sendCommand(
        command,
        new ResponseCallback {
          override def onErrorReceived(errorResponse: ErrorResponse): Unit    = promise.success(Left(errorResponse))
          override def onResultReceived(resultResponse: ResultResponse): Unit = promise.success(Right(resultResponse))
        }
      ))
    promise.future
  }

  private[this] implicit val system = ActorSystem("liquidity")
  private[this] implicit val mat    = ActorMaterializer()

  override protected type FixtureParam = TestSubscriber.Probe[ConnectionState]

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    Security.addProvider(new BouncyCastleProvider)
  }

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    handlerWrapperFactory.synchronized(handlerWrappers.foreach(_.quit()))
    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
    super.afterAll()
  }

  implicit override val patienceConfig =
    PatienceConfig(timeout = scaled(Span(5, Seconds)), interval = scaled(Span(1, Second)))

  override protected def withFixture(test: OneArgTest) = {
    val (queue, sub) = Source
      .queue[ConnectionState](bufferSize = 0, overflowStrategy = OverflowStrategy.backpressure)
      .toMat(TestSink.probe)(Keep.both)
      .run()
    val connectionStateListener = new ConnectionStateListener {
      override def onConnectionStateChanged(connectionState: ConnectionState): Unit = queue.offer(connectionState)
    }
    serverConnection.registerListener(connectionStateListener)
    try withFixture(test.toNoArgTest(sub))
    finally {
      serverConnection.unregisterListener(connectionStateListener)
      sub.cancel()
    }
  }

  "The server" should {
    "accept connections" in { sub =>
      val connectionRequestToken = new ConnectionRequestToken
      sub.requestNext(AVAILABLE)
      MainHandlerWrapper.post(serverConnection.requestConnection(connectionRequestToken, retry = false))
      sub.requestNext(CONNECTING)
      sub.requestNext(WAITING_FOR_VERSION_CHECK)
      sub.requestNext(ONLINE)
      MainHandlerWrapper.post(serverConnection.unrequestConnection(connectionRequestToken))
      sub.requestNext(DISCONNECTING)
      sub.requestNext(AVAILABLE)
    }
    s"admit joining the sentinel zone, ${SentinelZone.id} and recover the expected state" in { sub =>
      val connectionRequestToken = new ConnectionRequestToken
      sub.requestNext(AVAILABLE)
      MainHandlerWrapper.post(serverConnection.requestConnection(connectionRequestToken, retry = false))
      sub.requestNext(CONNECTING)
      sub.requestNext(WAITING_FOR_VERSION_CHECK)
      sub.requestNext(ONLINE)
      val result = send(
        JoinZoneCommand(SentinelZone.id)
      ).futureValue
      inside(result) {
        case Right(JoinZoneResponse(zone, connectedClients)) =>
          zone shouldBe SentinelZone
          connectedClients shouldBe Set(serverConnection.clientKey)
      }
      MainHandlerWrapper.post(serverConnection.unrequestConnection(connectionRequestToken))
      sub.requestNext(DISCONNECTING)
      sub.requestNext(AVAILABLE)
    }
  }
}
