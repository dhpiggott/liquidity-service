package com.dhpcs.liquidity.service

import java.io.{ByteArrayInputStream, File}
import java.security.cert.CertificateFactory
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.security.{KeyPairGenerator, KeyStore, SecureRandom}
import java.time.Instant
import java.util.UUID

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.grpc.GrpcClientSettings
import akka.http.scaladsl.model._
import akka.http.scaladsl.{ConnectionContext, Http, HttpsConnectionContext}
import akka.stream.scaladsl.Source
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit.TestKit
import cats.instances.list._
import cats.syntax.applicative._
import cats.syntax.traverse._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.grpc.protocol.LiquidityServiceClient
import com.dhpcs.liquidity.service.LiquidityServerComponentSpec._
import com.dhpcs.liquidity.service.LiquidityServerSpec._
import com.dhpcs.liquidity.service.SqlBindings._
import com.google.protobuf.struct.{Struct, Value}
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader, JWSObject, Payload}
import doobie._
import doobie.implicits._
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import org.json4s._
import org.json4s.native.JsonMethods
import org.scalactic.TripleEqualsSupport.Spread
import org.scalatest.Inside._
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import org.scalatest.{BeforeAndAfterAll, FreeSpec}
import zio.interop.catz._
import zio.{DefaultRuntime, Task}

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.sys.process.{Process, ProcessBuilder}

class LiquidityServerComponentSpec extends LiquidityServerSpec {

  private[this] val projectName = UUID.randomUUID().toString

  protected[this] override lazy val httpsConnectionContext
      : HttpsConnectionContext = {
    val (_, certgenPort) =
      externalDockerComposeServicePorts(projectName, "certgen", 80).head
    val certbundle = LiquidityServer
      .loadHttpCertBundle(Uri(s"http://localhost:$certgenPort/certbundle.zip"))
      .futureValue
    val keyManagerFactory = KeyManagerFactory
      .getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keyManagerFactory.init(
      null,
      Array.emptyCharArray
    )
    LiquidityServerSpec.httpsConnectionContext(
      keyManagerFactory,
      trustManagerFactory(certbundle)
    )
  }

  protected[this] override lazy val baseUri: Uri = {
    val (_, akkaHttpPort) =
      externalDockerComposeServicePorts(projectName, "client-relay", 8443).head
    Uri(s"https://localhost:$akkaHttpPort")
  }

  protected[this] override lazy val analyticsTransactor: Transactor[Task] = {
    val (_, mysqlPort) =
      externalDockerComposeServicePorts(projectName, "mysql", 3306).head
    Transactor.fromDriverManager[Task](
      driver = "com.mysql.cj.jdbc.Driver",
      url = urlFor(s"localhost:$mysqlPort", Some("liquidity_analytics")),
      user = "root",
      pass = ""
    )
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    assert(dockerCompose(projectName, "up", "--build", "-d").! === 0)
    val connectionTest = for (_ <- sql"SELECT 1".query[Int].unique) yield ()
    val (_, mysqlPort) =
      externalDockerComposeServicePorts(projectName, "mysql", 3306).head
    val transactor = Transactor.fromDriverManager[Task](
      driver = "com.mysql.cj.jdbc.Driver",
      url = urlFor(s"localhost:$mysqlPort"),
      user = "root",
      pass = ""
    )
    eventually(Timeout(60.seconds))(
      runtime.unsafeRun(
        connectionTest
          .transact(transactor)
      )
    )
    runtime.unsafeRun(
      execSqlFile("schemas/journal.sql")
        .transact(transactor)
    )
    runtime.unsafeRun(
      execSqlFile("schemas/analytics.sql")
        .transact(transactor)
    )

    eventually(Timeout(5.seconds)) {
      val (_, certgenPort) =
        externalDockerComposeServicePorts(projectName, "certgen", 80).head
      val response = Http(system.toUntyped)
        .singleRequest(
          HttpRequest(
            uri = Uri(s"http://localhost:$certgenPort/certbundle.zip")
          )
        )
        .futureValue
      assert(response.status === StatusCodes.OK)
    }
    eventually(Timeout(60.seconds)) {
      def statusIsOk(serviceName: String): Unit = {
        val (_, akkaHttpPort) =
          externalDockerComposeServicePorts(projectName, serviceName, 8443).head
        val response = Http(system.toUntyped)
          .singleRequest(
            HttpRequest(
              uri = Uri(s"https://localhost:$akkaHttpPort/ready")
            ),
            httpsConnectionContext
          )
          .futureValue
        assert(response.status === StatusCodes.OK)
        ()
      }
      statusIsOk("zone-host")
      statusIsOk("client-relay")
      statusIsOk("analytics")
    }
  }

  override protected def afterAll(): Unit = {
    assert(dockerCompose(projectName, "logs", "mysql").! === 0)
    assert(dockerCompose(projectName, "logs", "zone-host").! === 0)
    assert(dockerCompose(projectName, "logs", "client-relay").! === 0)
    assert(dockerCompose(projectName, "logs", "analytics").! === 0)
    assert(
      dockerCompose(projectName, "down", "--rmi", "local", "--volumes").! === 0
    )
    super.afterAll()
  }
}

object LiquidityServerComponentSpec {

  private def externalDockerComposeServicePorts(
      projectName: String,
      serviceName: String,
      internalPort: Int
  ): Map[String, Int] = {
    val serviceInstances =
      dockerCompose(projectName, "ps", "-q", serviceName).!!.split('\n')
    val ExternalIpAndPortRegex = "^(.+):(\\d+)$".r
    serviceInstances.zipWithIndex.map {
      case (instanceId, index) =>
        val ExternalIpAndPortRegex(_, externalPort) =
          dockerCompose(
            projectName,
            "port",
            "--index",
            (index + 1).toString,
            serviceName,
            internalPort.toString
          ).!!.trim
        instanceId -> externalPort.toInt
    }.toMap
  }

  private def dockerCompose(
      projectName: String,
      commandArgs: String*
  ): ProcessBuilder =
    Process(
      command = Seq(
        "docker-compose",
        "--project-name",
        projectName,
        "--file",
        new File("service/src/it/docker-compose.yml").getCanonicalPath
      ) ++
        commandArgs,
      cwd = None,
      extraEnv = "TAG" -> BuildInfo.version
    )

  private def execSqlFile(path: String): ConnectionIO[Unit] = {
    val source = scala.io.Source
      .fromFile(path)
    try source
      .mkString("")
      .split(';')
      .filter(!_.trim.isEmpty)
      .map(Fragment.const0(_).update.run)
      .toList
      .sequence
      .map(_ => ())
    finally source.close()
  }

  private def trustManagerFactory(
      certBundle: LiquidityServer.CertBundle
  ): TrustManagerFactory = {
    val certificateFactory = CertificateFactory.getInstance("X.509")
    val fullChain = certificateFactory.generateCertificates(
      new ByteArrayInputStream(certBundle.fullChainPem.toArray)
    )
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
    keyStore.load(null, Array.emptyCharArray)
    keyStore.setCertificateEntry(
      "identity",
      fullChain.asScala.last
    )
    val trustManagerFactory =
      TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trustManagerFactory.init(
      keyStore
    )
    trustManagerFactory
  }
}

class LiquidityServerIntegrationSpec extends LiquidityServerSpec {

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    eventually {
      val response = Http(system.toUntyped)
        .singleRequest(
          HttpRequest(
            uri = baseUri.withPath(Uri.Path("/ready"))
          ),
          httpsConnectionContext
        )
        .futureValue
      assert(response.status === StatusCodes.OK)
      ()
    }
  }

  protected[this] override val httpsConnectionContext
      : HttpsConnectionContext = {
    val keyManagerFactory = KeyManagerFactory
      .getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keyManagerFactory.init(
      null,
      Array.emptyCharArray
    )
    val trustManagerFactory = TrustManagerFactory
      .getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trustManagerFactory.init(
      null: KeyStore
    )
    LiquidityServerSpec.httpsConnectionContext(
      keyManagerFactory,
      trustManagerFactory
    )
  }

  protected[this] override val baseUri: Uri =
    Uri(s"https://${sys.env("SUBDOMAIN")}.liquidityapp.com")

  protected[this] override val analyticsTransactor: Transactor[Task] =
    Transactor.fromDriverManager[Task](
      driver = "com.mysql.cj.jdbc.Driver",
      url = urlFor(sys.env("MYSQL_HOSTNAME"), Some("liquidity_analytics")),
      user = sys.env("MYSQL_USERNAME"),
      pass = sys.env("MYSQL_PASSWORD")
    )

}

abstract class LiquidityServerSpec
    extends FreeSpec
    with BeforeAndAfterAll
    with Eventually
    with IntegrationPatience
    with ScalaFutures {

  private[this] def grpcClient = LiquidityServiceClient(
    GrpcClientSettings
      .connectToServiceAt(
        host = baseUri.authority.host.address(),
        port = baseUri.effectivePort
      )(system.toUntyped)
      .withSSLContext(
        httpsConnectionContext.sslContext
      )
  )

  "LiquidityServer" - {
    "accepts, broadcasts and projects create zone GRPC commands" in {
      val (createdZone, createdBalances) = createZone().futureValue
      zoneCreated(createdZone, createdBalances)
      ()
    }
    "accepts, broadcasts and projects change zone name GRPC commands" in {
      val (createdZone, createdBalances) = createZone().futureValue
      zoneCreated(createdZone, createdBalances)
      val zoneNotificationTestProbe =
        zoneNotificationSourceGrpc(createdZone.id)
          .runWith(
            TestSink
              .probe[proto.grpc.protocol.ZoneNotification](system.toUntyped)
          )
      zoneNotificationTestProbe.requestNext()
      zoneNotificationTestProbe.requestNext()
      val changedName = changeZoneName(createdZone.id).futureValue
      zoneNameChanged(createdZone, changedName, zoneNotificationTestProbe)
      zoneNotificationTestProbe.cancel()
      ()
    }
    "accepts, broadcasts and projects create member GRPC commands" in {
      val (createdZone, createdBalances) = createZone().futureValue
      zoneCreated(createdZone, createdBalances)
      val zoneNotificationTestProbe =
        zoneNotificationSourceGrpc(createdZone.id)
          .runWith(
            TestSink
              .probe[proto.grpc.protocol.ZoneNotification](system.toUntyped)
          )
      zoneNotificationTestProbe.requestNext()
      zoneNotificationTestProbe.requestNext()
      val createdMember = createMember(createdZone.id).futureValue
      memberCreated(createdZone, createdMember, zoneNotificationTestProbe)
      zoneNotificationTestProbe.cancel
      ()
    }
    "accepts, broadcasts and projects update member GRPC commands" in {
      val (createdZone, createdBalances) = createZone().futureValue
      zoneCreated(createdZone, createdBalances)
      val zoneNotificationTestProbe =
        zoneNotificationSourceGrpc(createdZone.id)
          .runWith(
            TestSink
              .probe[proto.grpc.protocol.ZoneNotification](system.toUntyped)
          )
      zoneNotificationTestProbe.requestNext()
      zoneNotificationTestProbe.requestNext()
      val createdMember = createMember(createdZone.id).futureValue
      val zoneWithCreatedMember =
        memberCreated(createdZone, createdMember, zoneNotificationTestProbe)
      val updatedMember =
        updateMember(createdZone.id, createdMember).futureValue
      memberUpdated(
        zoneWithCreatedMember,
        updatedMember,
        zoneNotificationTestProbe
      )
      zoneNotificationTestProbe.cancel()
      ()
    }
    "accepts, broadcasts and projects create account GRPC commands" in {
      val (createdZone, createdBalances) = createZone().futureValue
      zoneCreated(createdZone, createdBalances)
      val zoneNotificationTestProbe =
        zoneNotificationSourceGrpc(createdZone.id)
          .runWith(
            TestSink
              .probe[proto.grpc.protocol.ZoneNotification](system.toUntyped)
          )
      zoneNotificationTestProbe.requestNext()
      zoneNotificationTestProbe.requestNext()
      val createdMember = createMember(createdZone.id).futureValue
      val zoneWithCreatedMember =
        memberCreated(createdZone, createdMember, zoneNotificationTestProbe)
      val (createdAccount, _) =
        createAccount(createdZone.id, owner = createdMember.id).futureValue
      accountCreated(
        zoneWithCreatedMember,
        createdBalances,
        createdAccount,
        zoneNotificationTestProbe
      )
      zoneNotificationTestProbe.cancel()
      ()
    }
    "accepts, broadcasts and projects update account GRPC commands" in {
      val (createdZone, createdBalances) = createZone().futureValue
      zoneCreated(createdZone, createdBalances)
      val zoneNotificationTestProbe =
        zoneNotificationSourceGrpc(createdZone.id)
          .runWith(
            TestSink
              .probe[proto.grpc.protocol.ZoneNotification](system.toUntyped)
          )
      zoneNotificationTestProbe.requestNext()
      zoneNotificationTestProbe.requestNext()
      val createdMember = createMember(createdZone.id).futureValue
      val zoneWithCreatedMember =
        memberCreated(createdZone, createdMember, zoneNotificationTestProbe)
      val (createdAccount, _) =
        createAccount(createdZone.id, owner = createdMember.id).futureValue
      val (zoneWithCreatedAccount, _) =
        accountCreated(
          zoneWithCreatedMember,
          createdBalances,
          createdAccount,
          zoneNotificationTestProbe
        )
      val updatedAccount =
        updateAccount(createdZone.id, createdAccount).futureValue
      accountUpdated(
        zoneWithCreatedAccount,
        updatedAccount,
        zoneNotificationTestProbe
      )
      zoneNotificationTestProbe.cancel()
      ()
    }
    "accepts, broadcasts and projects add transaction GRPC commands" in {
      val (createdZone, createdBalances) = createZone().futureValue
      zoneCreated(createdZone, createdBalances)
      val zoneNotificationTestProbe =
        zoneNotificationSourceGrpc(createdZone.id)
          .runWith(
            TestSink
              .probe[proto.grpc.protocol.ZoneNotification](system.toUntyped)
          )
      zoneNotificationTestProbe.requestNext()
      zoneNotificationTestProbe.requestNext()
      val createdMember = createMember(createdZone.id).futureValue
      val zoneWithCreatedMember =
        memberCreated(createdZone, createdMember, zoneNotificationTestProbe)
      val (createdAccount, _) =
        createAccount(createdZone.id, owner = createdMember.id).futureValue
      val (zoneWithCreatedAccount, updatedBalances) =
        accountCreated(
          zoneWithCreatedMember,
          createdBalances,
          createdAccount,
          zoneNotificationTestProbe
        )
      val addedTransaction = addTransaction(
        createdZone.id,
        createdZone,
        to = createdAccount.id
      ).futureValue
      transactionAdded(
        zoneWithCreatedAccount,
        updatedBalances,
        addedTransaction,
        zoneNotificationTestProbe
      )
      zoneNotificationTestProbe.cancel()
      ()
    }
    "sends GRPC subscribers Empty notifications when left idle" in {
      val (createdZone, createdBalances) = createZone().futureValue
      zoneCreated(createdZone, createdBalances)
      val zoneNotificationTestProbe =
        zoneNotificationSourceGrpc(createdZone.id)
          .runWith(
            TestSink
              .probe[proto.grpc.protocol.ZoneNotification](system.toUntyped)
          )
      inside(zoneNotificationTestProbe.requestNext()) {
        case proto.grpc.protocol.ZoneStateNotification(_, _) => ()
      }
      inside(zoneNotificationTestProbe.requestNext()) {
        case proto.grpc.protocol.ClientJoinedZoneNotification(_, _) => ()
      }
      zoneNotificationTestProbe.within(15.seconds)(
        inside(zoneNotificationTestProbe.requestNext()) {
          case proto.grpc.protocol.ZoneNotification.Empty => ()
        }
      )
      zoneNotificationTestProbe.cancel()
    }
  }

  protected[this] val runtime: DefaultRuntime = new DefaultRuntime {}

  protected[this] implicit val system: ActorSystem[Nothing] =
    ActorSystem(Behaviors.empty, "liquiditySpec")
  protected[this] implicit val mat: Materializer =
    ActorMaterializer()(system.toUntyped)
  protected[this] implicit val ec: ExecutionContext = system.executionContext

  private[this] def createZone()(
      implicit ec: ExecutionContext
  ): Future[(proto.model.Zone, Map[String, BigDecimal])] = {
    for (zoneResponse <- grpcClient
           .createZone()
           .addHeader("Authorization", s"Bearer $selfSignedJwt")
           .invoke(
             proto.grpc.protocol.CreateZoneCommand(
               equityOwnerPublicKey = com.google.protobuf.ByteString
                 .copyFrom(rsaPublicKey.getEncoded),
               equityOwnerName = Some("Dave"),
               equityOwnerMetadata = None,
               equityAccountName = None,
               equityAccountMetadata = None,
               name = Some("Dave's Game"),
               metadata = Some(
                 Struct(
                   Map(
                     "isTest" -> Value.defaultInstance.withBoolValue(true)
                   )
                 )
               )
             )
           ))
      yield zoneResponse match {
        case proto.grpc.protocol.CreateZoneResponse(
            proto.grpc.protocol.CreateZoneResponse.Result.Success(
              proto.grpc.protocol.CreateZoneResponse.Success(Some(zone))
            )
            ) =>
          assert(zone.accounts.size === 1)
          assert(zone.members.size === 1)
          val equityAccount =
            zone.accounts.find(_.id == zone.equityAccountId).head
          val equityAccountOwner =
            zone.members.find(_.id == equityAccount.ownerMemberIds.head).head
          assert(
            equityAccount === proto.model.Account(
              equityAccount.id,
              ownerMemberIds = Seq(equityAccountOwner.id),
              name = None,
              metadata = None
            )
          )
          assert(
            equityAccountOwner === proto.model.Member(
              equityAccountOwner.id,
              ownerPublicKeys = Seq(
                com.google.protobuf.ByteString
                  .copyFrom(rsaPublicKey.getEncoded)
              ),
              name = Some("Dave"),
              metadata = None
            )
          )
          assert(
            zone.created === Spread(
              pivot = Instant.now().toEpochMilli,
              tolerance = 5000
            )
          )
          assert(
            zone.expires === zone.created + java.time.Duration
              .ofDays(30)
              .toMillis
          )
          assert(zone.transactions === Seq.empty)
          assert(zone.name === Some("Dave's Game"))
          assert(
            zone.metadata === Some(
              Struct(
                Map(
                  "isTest" -> Value.defaultInstance.withBoolValue(true)
                )
              )
            )
          )
          (
            zone,
            Map(zone.equityAccountId -> BigDecimal(0))
          )

        case _ =>
          fail()
      }
  }

  private[this] def zoneCreated(
      zone: proto.model.Zone,
      balances: Map[String, BigDecimal]
  ): (proto.model.Zone, Map[String, BigDecimal]) =
    (
      awaitZoneProjection(zone),
      awaitZoneBalancesProjection(zone.id, balances)
    )

  private[this] def zoneNotificationSourceGrpc(
      zoneId: String
  ): Source[proto.grpc.protocol.ZoneNotification, NotUsed] =
    grpcClient
      .zoneNotifications()
      .addHeader("Authorization", s"Bearer $selfSignedJwt")
      .invoke(
        proto.grpc.protocol.ZoneSubscription(
          zoneId = zoneId
        )
      )
      .map(_.toZoneNotification)

  private[this] def changeZoneName(
      zoneId: String
  )(implicit ec: ExecutionContext): Future[Option[String]] = {
    val changedName = None
    for (zoneResponse <- grpcClient
           .changeZoneName()
           .addHeader("Authorization", s"Bearer $selfSignedJwt")
           .invoke(
             proto.grpc.protocol.ChangeZoneNameCommand(
               zoneId = zoneId,
               name = changedName
             )
           )) yield {
      assert(
        zoneResponse === proto.grpc.protocol.ChangeZoneNameResponse(
          proto.grpc.protocol.ChangeZoneNameResponse.Result.Success(
            com.google.protobuf.empty.Empty.defaultInstance
          )
        )
      )
      changedName
    }
  }

  private[this] def zoneNameChanged(
      zone: proto.model.Zone,
      name: Option[String],
      zoneNotificationTestProbe: TestSubscriber.Probe[
        proto.grpc.protocol.ZoneNotification
      ]
  ): proto.model.Zone = {
    inside(zoneNotificationTestProbe.requestNext()) {
      case zoneNameChangedNotification: proto.grpc.protocol.ZoneNameChangedNotification =>
        assert(zoneNameChangedNotification.name === name)
    }
    awaitZoneProjection(zone.copy(name = name))
  }

  private[this] def createMember(
      zoneId: String
  )(implicit ec: ExecutionContext): Future[proto.model.Member] = {
    for (zoneResponse <- grpcClient
           .createMember()
           .addHeader("Authorization", s"Bearer $selfSignedJwt")
           .invoke(
             proto.grpc.protocol.CreateMemberCommand(
               zoneId,
               ownerPublicKeys = Seq(
                 com.google.protobuf.ByteString
                   .copyFrom(rsaPublicKey.getEncoded)
               ),
               name = Some("Jenny"),
               metadata = None
             )
           ))
      yield zoneResponse match {
        case proto.grpc.protocol.CreateMemberResponse(
            proto.grpc.protocol.CreateMemberResponse.Result.Success(
              proto.grpc.protocol.CreateMemberResponse.Success(Some(member))
            )
            ) =>
          assert(
            member.ownerPublicKeys === Seq(
              com.google.protobuf.ByteString.copyFrom(rsaPublicKey.getEncoded)
            )
          )
          assert(member.name === Some("Jenny"))
          assert(member.metadata === None)
          member

        case _ =>
          fail()
      }
  }

  private[this] def memberCreated(
      zone: proto.model.Zone,
      member: proto.model.Member,
      zoneNotificationTestProbe: TestSubscriber.Probe[
        proto.grpc.protocol.ZoneNotification
      ]
  ): proto.model.Zone = {
    inside(zoneNotificationTestProbe.requestNext()) {
      case memberCreatedNotification: proto.grpc.protocol.MemberCreatedNotification =>
        assert(memberCreatedNotification.member === Some(member))
    }
    awaitZoneProjection(
      zone.copy(
        members = zone.members.filterNot(_.id == member.id) :+ member
      )
    )
  }

  private[this] def updateMember(zoneId: String, member: proto.model.Member)(
      implicit ec: ExecutionContext
  ): Future[proto.model.Member] = {
    val updatedMember = member.copy(name = None)
    for (zoneResponse <- grpcClient
           .updateMember()
           .addHeader("Authorization", s"Bearer $selfSignedJwt")
           .invoke(
             proto.grpc.protocol.UpdateMemberCommand(
               zoneId,
               Some(updatedMember)
             )
           )) yield {
      assert(
        zoneResponse === proto.grpc.protocol.UpdateMemberResponse(
          proto.grpc.protocol.UpdateMemberResponse.Result.Success(
            com.google.protobuf.empty.Empty.defaultInstance
          )
        )
      )
      updatedMember
    }
  }

  private[this] def memberUpdated(
      zone: proto.model.Zone,
      member: proto.model.Member,
      zoneNotificationTestProbe: TestSubscriber.Probe[
        proto.grpc.protocol.ZoneNotification
      ]
  ): proto.model.Zone = {
    inside(zoneNotificationTestProbe.requestNext()) {
      case memberUpdatedNotification: proto.grpc.protocol.MemberUpdatedNotification =>
        assert(memberUpdatedNotification.member === Some(member))
    }
    awaitZoneProjection(
      zone.copy(
        members = zone.members.filterNot(_.id == member.id) :+ member
      )
    )
  }

  private[this] def createAccount(zoneId: String, owner: String)(
      implicit ec: ExecutionContext
  ): Future[(proto.model.Account, BigDecimal)] = {
    for (zoneResponse <- grpcClient
           .createAccount()
           .addHeader("Authorization", s"Bearer $selfSignedJwt")
           .invoke(
             proto.grpc.protocol.CreateAccountCommand(
               zoneId = zoneId,
               ownerMemberIds = Seq(owner),
               name = Some("Jenny's Account"),
               metadata = None
             )
           ))
      yield zoneResponse match {
        case proto.grpc.protocol.CreateAccountResponse(
            proto.grpc.protocol.CreateAccountResponse.Result.Success(
              proto.grpc.protocol.CreateAccountResponse.Success(Some(account))
            )
            ) =>
          assert(account.ownerMemberIds === Seq(owner))
          assert(account.name === Some("Jenny's Account"))
          assert(account.metadata === None)
          account -> BigDecimal(0)

        case _ =>
          fail()
      }
  }

  private[this] def accountCreated(
      zone: proto.model.Zone,
      balances: Map[String, BigDecimal],
      account: proto.model.Account,
      zoneNotificationTestProbe: TestSubscriber.Probe[
        proto.grpc.protocol.ZoneNotification
      ]
  ): (proto.model.Zone, Map[String, BigDecimal]) = {
    inside(zoneNotificationTestProbe.requestNext()) {
      case accountCreatedNotification: proto.grpc.protocol.AccountCreatedNotification =>
        assert(accountCreatedNotification.account === Some(account))
    }
    (
      awaitZoneProjection(
        zone.copy(
          accounts = zone.accounts.filterNot(_.id == account.id) :+ account
        )
      ),
      awaitZoneBalancesProjection(
        zone.id,
        balances + (account.id -> BigDecimal(0))
      )
    )
  }

  private[this] def updateAccount(zoneId: String, account: proto.model.Account)(
      implicit ec: ExecutionContext
  ): Future[proto.model.Account] = {
    val updatedAccount = account.copy(name = None)
    for (zoneResponse <- grpcClient
           .updateAccount()
           .addHeader("Authorization", s"Bearer $selfSignedJwt")
           .invoke(
             proto.grpc.protocol.UpdateAccountCommand(
               zoneId,
               actingAs = account.ownerMemberIds.head,
               Some(updatedAccount)
             )
           )) yield {

      assert(
        zoneResponse === proto.grpc.protocol.UpdateAccountResponse(
          proto.grpc.protocol.UpdateAccountResponse.Result.Success(
            com.google.protobuf.empty.Empty.defaultInstance
          )
        )
      )
      updatedAccount
    }
  }

  private[this] def accountUpdated(
      zone: proto.model.Zone,
      account: proto.model.Account,
      zoneNotificationTestProbe: TestSubscriber.Probe[
        proto.grpc.protocol.ZoneNotification
      ]
  ): proto.model.Zone = {
    inside(zoneNotificationTestProbe.requestNext()) {
      case accountUpdatedNotification: proto.grpc.protocol.AccountUpdatedNotification =>
        assert(accountUpdatedNotification.account === Some(account))
    }
    awaitZoneProjection(
      zone.copy(
        accounts = zone.accounts.filterNot(_.id == account.id) :+ account
      )
    )
  }

  private[this] def addTransaction(
      zoneId: String,
      zone: proto.model.Zone,
      to: String
  )(implicit ec: ExecutionContext): Future[proto.model.Transaction] = {
    for (zoneResponse <- grpcClient
           .addTransaction()
           .addHeader("Authorization", s"Bearer $selfSignedJwt")
           .invoke(
             proto.grpc.protocol.AddTransactionCommand(
               zoneId,
               actingAs = zone.accounts
                 .find(_.id == zone.equityAccountId)
                 .head
                 .ownerMemberIds
                 .head,
               from = zone.equityAccountId,
               to = to,
               value = "5000000000000000000000",
               description = Some("Jenny's Lottery Win"),
               metadata = None
             )
           ))
      yield zoneResponse match {
        case proto.grpc.protocol.AddTransactionResponse(
            proto.grpc.protocol.AddTransactionResponse.Result.Success(
              proto.grpc.protocol.AddTransactionResponse
                .Success(Some(transaction))
            )
            ) =>
          assert(transaction.from === zone.equityAccountId)
          assert(transaction.to === to)
          assert(transaction.value === "5000000000000000000000")
          assert(
            transaction.creator === zone.accounts
              .find(_.id == zone.equityAccountId)
              .head
              .ownerMemberIds
              .head
          )
          assert(
            transaction.created === Spread(
              pivot = Instant.now().toEpochMilli,
              tolerance = 5000
            )
          )
          assert(transaction.description === Some("Jenny's Lottery Win"))
          assert(transaction.metadata === None)
          transaction

        case _ =>
          fail()
      }
  }

  private[this] def transactionAdded(
      zone: proto.model.Zone,
      balances: Map[String, BigDecimal],
      transaction: proto.model.Transaction,
      zoneNotificationTestProbe: TestSubscriber.Probe[
        proto.grpc.protocol.ZoneNotification
      ]
  ): (proto.model.Zone, Map[String, BigDecimal]) = {
    inside(zoneNotificationTestProbe.requestNext()) {
      case transactionAddedNotification: proto.grpc.protocol.TransactionAddedNotification =>
        assert(transactionAddedNotification.transaction === Some(transaction))
    }
    (
      awaitZoneProjection(
        zone.copy(
          transactions = zone.transactions
            .filterNot(_.id == transaction.id) :+ transaction
        )
      ),
      awaitZoneBalancesProjection(
        zone.id,
        balances +
          (transaction.from -> (balances(transaction.from) - BigDecimal(
            transaction.value
          ))) +
          (transaction.to -> (balances(transaction.to) + BigDecimal(
            transaction.value
          )))
      )
    )
  }

  private[this] def awaitZoneProjection(
      zone: proto.model.Zone
  ): proto.model.Zone = {
    val retrieveAllMembers: ConnectionIO[Seq[(String, proto.model.Member)]] = {
      def retrieve(
          memberId: String
      ): ConnectionIO[(String, proto.model.Member)] = {
        for {
          ownerPublicKeys <- sql"""
           SELECT devices.public_key
             FROM member_owners
             JOIN devices
             ON devices.fingerprint = member_owners.fingerprint
             WHERE member_owners.update_id = (
               SELECT update_id
                 FROM member_updates
                 WHERE zone_id = ${zone.id}
                 AND member_id = $memberId
                 ORDER BY update_id
                 DESC
                 LIMIT 1
             )
          """
            .query[Array[Byte]]
            .to[Vector]
          nameAndMetadata <- sql"""
           SELECT name, metadata
             FROM member_updates
             WHERE zone_id = ${zone.id}
             AND member_id = $memberId
             ORDER BY update_id
             DESC
             LIMIT 1
          """
            .query[(Option[String], Option[com.google.protobuf.struct.Struct])]
            .unique
          (name, metadata) = nameAndMetadata
        } yield memberId -> proto.model.Member(
          memberId,
          ownerPublicKeys.map(com.google.protobuf.ByteString.copyFrom),
          name,
          metadata
        )
      }

      for {
        memberIds <- sql"""
         SELECT member_id
           FROM members
           WHERE zone_id = ${zone.id}
        """
          .query[String]
          .to[Vector]
        members <- memberIds
          .map(retrieve)
          .toList
          .sequence
      } yield members
    }
    val retrieveAllAccounts
        : ConnectionIO[Seq[(String, proto.model.Account)]] = {
      def retrieve(
          accountId: String
      ): ConnectionIO[(String, proto.model.Account)] = {
        for {
          ownerMemberIds <- sql"""
           SELECT member_id
             FROM account_owners
             WHERE update_id = (
               SELECT update_id
                 FROM account_updates
                 WHERE zone_id = ${zone.id}
                 AND account_id = $accountId
                 ORDER BY update_id
                 DESC
                 LIMIT 1
             )
          """
            .query[String]
            .to[Vector]
          nameAndMetadata <- sql"""
           SELECT name, metadata
             FROM account_updates
             WHERE zone_id = ${zone.id}
             AND account_id = $accountId
             ORDER BY update_id
             DESC
             LIMIT 1
          """
            .query[(Option[String], Option[com.google.protobuf.struct.Struct])]
            .unique
          (name, metadata) = nameAndMetadata
        } yield accountId -> proto.model.Account(
          accountId,
          ownerMemberIds,
          name,
          metadata
        )
      }

      for {
        accountIds <- sql"""
         SELECT account_id
           FROM accounts
           WHERE zone_id = ${zone.id}
        """
          .query[String]
          .to[Vector]
        accounts <- accountIds
          .map(retrieve)
          .toList
          .sequence
      } yield accounts
    }
    val retrieveAllTransactions
        : ConnectionIO[Seq[(String, proto.model.Transaction)]] =
      sql"""
       SELECT transaction_id, `from`, `to`, `value`, creator, created, description, metadata
         FROM transactions
         WHERE zone_id = ${zone.id}
      """
        .query[
          (
              String,
              String,
              String,
              BigDecimal,
              String,
              Instant,
              Option[String],
              Option[com.google.protobuf.struct.Struct]
          )
        ]
        .to[Vector]
        .map(_.map {
          case (id, from, to, value, creator, created, description, metadata) =>
            id -> proto.model.Transaction(
              id,
              from,
              to,
              value.toString,
              creator,
              created.toEpochMilli,
              description,
              metadata
            )
        })
    val retrieveOption: ConnectionIO[Option[proto.model.Zone]] =
      for {
        maybeZoneMetadata <- sql"""
         SELECT zone_name_changes.name, zones.equity_account_id, zones.created, zones.expires, zones.metadata
           FROM zones
           JOIN zone_name_changes
           ON zone_name_changes.change_id = (
             SELECT zone_name_changes.change_id
               FROM zone_name_changes
               WHERE zone_name_changes.zone_id = zones.zone_id
               ORDER BY change_id
               DESC
               LIMIT 1
           )
           WHERE zones.zone_id = ${zone.id}
        """
          .query[
            (
                Option[String],
                String,
                Instant,
                Instant,
                Option[com.google.protobuf.struct.Struct]
            )
          ]
          .option
        maybeZone <- maybeZoneMetadata match {
          case None =>
            None.pure[ConnectionIO]

          case Some((name, equityAccountId, created, expires, metadata)) =>
            for {
              members <- retrieveAllMembers.map(_.toMap)
              accounts <- retrieveAllAccounts.map(_.toMap)
              transactions <- retrieveAllTransactions.map(_.toMap)
            } yield Some(
              proto.model.Zone(
                zone.id,
                equityAccountId,
                members.values.toSeq,
                accounts.values.toSeq,
                transactions.values.toSeq,
                created.toEpochMilli,
                expires.toEpochMilli,
                name,
                metadata
              )
            )
        }
      } yield maybeZone
    eventually {
      assert(
        runtime.unsafeRun(
          retrieveOption
            .transact(analyticsTransactor)
        ) === Some(zone)
      )
    }
    zone
  }

  private[this] def awaitZoneBalancesProjection(
      zoneId: String,
      balances: Map[String, BigDecimal]
  ): Map[String, BigDecimal] = {
    val retrieveAll =
      sql"""
        SELECT account_id, balance
          FROM accounts
          WHERE zone_id = $zoneId
      """
        .query[(String, BigDecimal)]
        .to[Vector]
    eventually {
      assert(
        runtime
          .unsafeRun(
            retrieveAll
              .transact(analyticsTransactor)
          )
          .toMap === balances
      )
    }
    balances
  }

  protected[this] def httpsConnectionContext: HttpsConnectionContext

  protected[this] def baseUri: Uri

  protected[this] def analyticsTransactor: Transactor[Task]

  protected[this] def urlFor(
      authority: String,
      database: Option[String] = None
  ): String =
    s"jdbc:mysql://$authority${database.fold("")(database => s"/$database")}?" +
      "useSSL=false&" +
      "cacheCallableStmts=true&" +
      "cachePrepStmts=true&" +
      "cacheResultSetMetadata=true&" +
      "cacheServerConfiguration=true&" +
      "useLocalSessionState=true&" +
      "useServerPrepStmts=true"

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system.toUntyped)
    super.afterAll()
  }
}

object LiquidityServerSpec {

  def httpsConnectionContext(
      keyManagerFactory: KeyManagerFactory,
      trustManagerFactory: TrustManagerFactory
  ): HttpsConnectionContext = {
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(
      keyManagerFactory.getKeyManagers,
      trustManagerFactory.getTrustManagers,
      new SecureRandom
    )
    ConnectionContext.https(
      sslContext,
      enabledCipherSuites = Some(
        Seq(
          "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
          "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384",
          "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
          "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256"
        )
      ),
      enabledProtocols = Some(
        Seq(
          "TLSv1.2"
        )
      )
    )
  }

  val (rsaPrivateKey: RSAPrivateKey, rsaPublicKey: RSAPublicKey) = {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(2048)
    val keyPair = keyPairGenerator.generateKeyPair
    (keyPair.getPrivate, keyPair.getPublic)
  }

  private val selfSignedJwt = {
    val jws = new JWSObject(
      new JWSHeader.Builder(JWSAlgorithm.RS256).build(),
      new Payload(
        JsonMethods.compact(
          JsonMethods.render(
            JObject(
              "sub" -> JString(
                okio.ByteString.of(rsaPublicKey.getEncoded: _*).base64()
              )
            )
          )
        )
      )
    )
    jws.sign(new RSASSASigner(rsaPrivateKey))
    jws.serialize()
  }
}
