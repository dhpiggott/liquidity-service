package com.dhpcs.liquidity.service

import java.io.{ByteArrayInputStream, File}
import java.security.{KeyPairGenerator, KeyStore}
import java.security.cert.CertificateFactory
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.time.Instant
import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.{Http, HttpsConnectionContext}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.Source
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit.TestKit
import akka.util.ByteString
import cats.data.Validated
import cats.effect.{ContextShift, IO}
import cats.instances.list._
import cats.syntax.applicative._
import cats.syntax.traverse._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.service.LiquidityServerSpec._
import com.dhpcs.liquidity.service.LiquidityServerComponentSpec._
import com.dhpcs.liquidity.service.SqlBindings._
import com.dhpcs.liquidity.ws.protocol.ProtoBindings._
import com.dhpcs.liquidity.ws.protocol._
import com.google.protobuf.CodedInputStream
import com.google.protobuf.struct.{Struct, Value}
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader, JWSObject, Payload}
import com.nimbusds.jose.crypto.RSASSASigner
import doobie._
import doobie.implicits._
import javax.net.ssl.{KeyManagerFactory, TrustManagerFactory}
import org.scalactic.TripleEqualsSupport.Spread
import org.scalatest.Inside._
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import org.scalatest.{BeforeAndAfterAll, FreeSpec}
import play.api.libs.json.Json

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
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
    LiquidityServer.httpsConnectionContext(
      keyManagerFactory,
      trustManagerFactory(certbundle)
    )
  }

  protected[this] override lazy val baseUri: Uri = {
    val (_, akkaHttpPort) =
      externalDockerComposeServicePorts(projectName, "client-relay", 8443).head
    Uri(s"https://localhost:$akkaHttpPort")
  }

  protected[this] override lazy val analyticsTransactor: Transactor[IO] = {
    val (_, mysqlPort) =
      externalDockerComposeServicePorts(projectName, "mysql", 3306).head
    Transactor.fromDriverManager[IO](
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
    val transactor = Transactor.fromDriverManager[IO](
      driver = "com.mysql.cj.jdbc.Driver",
      url = urlFor(s"localhost:$mysqlPort"),
      user = "root",
      pass = ""
    )
    eventually(Timeout(60.seconds))(
      connectionTest
        .transact(transactor)
        .unsafeRunSync()
    )
    execSqlFile("schemas/journal.sql")
      .transact(transactor)
      .unsafeRunSync()
    execSqlFile("schemas/analytics.sql")
      .transact(transactor)
      .unsafeRunSync()
    execSqlFile("schemas/administrators.sql")
      .transact(transactor)
      .unsafeRunSync()
    addAdministrator(PublicKey(rsaPublicKey.getEncoded))
      .transact(transactor)
      .unsafeRunSync()
    eventually(Timeout(5.seconds)) {
      val (_, certgenPort) =
        externalDockerComposeServicePorts(projectName, "certgen", 80).head
      val response = Http()
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
        val response = Http()
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
          dockerCompose(projectName,
                        "port",
                        "--index",
                        (index + 1).toString,
                        serviceName,
                        internalPort.toString).!!.trim
        instanceId -> externalPort.toInt
    }.toMap
  }

  private def dockerCompose(projectName: String,
                            commandArgs: String*): ProcessBuilder =
    Process(
      command = Seq(
        "docker-compose",
        "--project-name",
        projectName,
        "--file",
        new File("service/src/it/docker-compose.yml").getCanonicalPath) ++
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

  private def addAdministrator(publicKey: PublicKey): ConnectionIO[Unit] =
    for (_ <- sql"""
             INSERT INTO liquidity_administrators.administrators (public_key)
               VALUES ($publicKey)
           """.update.run)
      yield ()

  private def trustManagerFactory(
      certBundle: LiquidityServer.CertBundle): TrustManagerFactory = {
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
      val response = Http()
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
    LiquidityServer.httpsConnectionContext(
      keyManagerFactory,
      trustManagerFactory
    )
  }

  protected[this] override val baseUri: Uri =
    Uri(s"https://${sys.env("SUBDOMAIN")}.liquidityapp.com")

  protected[this] override val analyticsTransactor: Transactor[IO] =
    Transactor.fromDriverManager[IO](
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

  "LiquidityServer" - {
    "accepts and projects create zone commands" in {
      val (createdZone, createdBalances) = createZone().futureValue
      zoneCreated(createdZone, createdBalances)
      ()
    }
    "accepts and projects change zone name commands" in {
      val (createdZone, createdBalances) = createZone().futureValue
      zoneCreated(createdZone, createdBalances)
      val changedName = changeZoneName(createdZone.id).futureValue
      zoneNameChanged(createdZone, changedName)
      ()
    }
    "accepts and projects create member commands" in {
      val (createdZone, createdBalances) = createZone().futureValue
      zoneCreated(createdZone, createdBalances)
      val createdMember = createMember(createdZone.id).futureValue
      memberCreated(createdZone, createdMember)
      ()
    }
    "accepts and projects update member commands" in {
      val (createdZone, createdBalances) = createZone().futureValue
      zoneCreated(createdZone, createdBalances)
      val createdMember = createMember(createdZone.id).futureValue
      val zoneWithCreatedMember = memberCreated(createdZone, createdMember)
      val updatedMember =
        updateMember(createdZone.id, createdMember).futureValue
      memberUpdated(zoneWithCreatedMember, updatedMember)
      ()
    }
    "accepts and projects create account commands" in {
      val (createdZone, createdBalances) = createZone().futureValue
      zoneCreated(createdZone, createdBalances)
      val createdMember = createMember(createdZone.id).futureValue
      val zoneWithCreatedMember = memberCreated(createdZone, createdMember)
      val (createdAccount, _) =
        createAccount(createdZone.id, owner = createdMember.id).futureValue
      accountCreated(zoneWithCreatedMember, createdBalances, createdAccount)
      ()
    }
    "accepts and projects update account commands" in {
      val (createdZone, createdBalances) = createZone().futureValue
      zoneCreated(createdZone, createdBalances)
      val createdMember = createMember(createdZone.id).futureValue
      val zoneWithCreatedMember = memberCreated(createdZone, createdMember)
      val (createdAccount, _) =
        createAccount(createdZone.id, owner = createdMember.id).futureValue
      val (zoneWithCreatedAccount, _) =
        accountCreated(zoneWithCreatedMember, createdBalances, createdAccount)
      val updatedAccount =
        updateAccount(createdZone.id, createdAccount).futureValue
      accountUpdated(zoneWithCreatedAccount, updatedAccount)
      ()
    }
    "accepts and projects add transaction commands" in {
      val (createdZone, createdBalances) = createZone().futureValue
      zoneCreated(createdZone, createdBalances)
      val createdMember = createMember(createdZone.id).futureValue
      val zoneWithCreatedMember = memberCreated(createdZone, createdMember)
      val (createdAccount, _) =
        createAccount(createdZone.id, owner = createdMember.id).futureValue
      val (zoneWithCreatedAccount, updatedBalances) =
        accountCreated(zoneWithCreatedMember, createdBalances, createdAccount)
      val addedTransaction =
        addTransaction(createdZone.id, createdZone, to = createdAccount.id).futureValue
      transactionAdded(zoneWithCreatedAccount,
                       updatedBalances,
                       addedTransaction)
      ()
    }
    "notifies subscribers of events and sends PingCommands when left idle" in {
      val (createdZone, createdBalances) = createZone().futureValue
      zoneCreated(createdZone, createdBalances)
      val zoneNotificationTestProbe =
        zoneNotificationSource(createdZone.id, selfSignedJwt)
          .runWith(TestSink.probe[ZoneNotification])
      inside(zoneNotificationTestProbe.requestNext()) {
        case ZoneStateNotification(_, _) => ()
      }
      inside(zoneNotificationTestProbe.requestNext()) {
        case ClientJoinedNotification(_, _) => ()
      }
      zoneNotificationTestProbe.within(10.seconds)(
        inside(zoneNotificationTestProbe.requestNext()) {
          case PingNotification() => ()
        }
      )
      zoneNotificationTestProbe.cancel()
    }
  }

  protected[this] implicit val contextShift: ContextShift[IO] =
    IO.contextShift(ExecutionContext.global)

  protected[this] implicit val system: ActorSystem = ActorSystem()
  protected[this] implicit val mat: Materializer = ActorMaterializer()
  protected[this] implicit val ec: ExecutionContext = system.dispatcher

  private[this] def createZone()(implicit ec: ExecutionContext)
    : Future[(Zone, Map[AccountId, BigDecimal])] =
    for (zoneResponse <- createZone(
           CreateZoneCommand(
             equityOwnerPublicKey = PublicKey(rsaPublicKey.getEncoded),
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
      yield
        zoneResponse match {
          case CreateZoneResponse(Validated.Valid(zone)) =>
            assert(zone.accounts.size === 1)
            assert(zone.members.size === 1)
            val equityAccount = zone.accounts(zone.equityAccountId)
            val equityAccountOwner =
              zone.members(equityAccount.ownerMemberIds.head)
            assert(
              equityAccount === Account(
                equityAccount.id,
                ownerMemberIds = Set(equityAccountOwner.id),
                name = None,
                metadata = None
              )
            )
            assert(
              equityAccountOwner === Member(
                equityAccountOwner.id,
                ownerPublicKeys = Set(PublicKey(rsaPublicKey.getEncoded)),
                name = Some("Dave"),
                metadata = None
              )
            )
            assert(
              zone.created.toEpochMilli === Spread(
                pivot = Instant.now().toEpochMilli,
                tolerance = 5000
              )
            )
            assert(
              zone.expires === zone.created.plus(java.time.Duration.ofDays(30))
            )
            assert(zone.transactions === Map.empty)
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

  private[this] def zoneCreated(zone: Zone,
                                balances: Map[AccountId, BigDecimal])
    : (Zone, Map[AccountId, BigDecimal]) =
    (
      awaitZoneProjection(zone),
      awaitZoneBalancesProjection(zone.id, balances)
    )

  private[this] def zoneNotificationSource(
      zoneId: ZoneId,
      selfSignedJwt: String): Source[ZoneNotification, NotUsed] = {
    val byteSource = Source
      .fromFuture(
        Http()
          .singleRequest(
            HttpRequest(
              uri = baseUri.withPath(
                Uri.Path("/zone") / zoneId.value
              ),
              headers = Seq(
                Authorization(OAuth2BearerToken(selfSignedJwt)),
                Accept(
                  MediaRange(
                    MediaType.customBinary(mainType = "application",
                                           subType = "x-protobuf",
                                           comp = MediaType.NotCompressible)
                  )
                )
              )
            ),
            httpsConnectionContext
          )
      )
      .flatMapConcat { response =>
        assert(response.status === StatusCodes.OK)
        assert(
          response.entity.contentType === ContentType(
            MediaType.customBinary(mainType = "application",
                                   subType = "x-protobuf",
                                   comp = MediaType.NotCompressible,
                                   params = Map("delimited" -> "true"))
          )
        )
        response.entity.dataBytes
      }
      .flatMapConcat(Source(_))
    val delimitedByteArraySource = byteSource.statefulMapConcat { () =>
      // Messages are length-delimited by varints where the MSB is set for all
      // but the last byte. (See
      // https://developers.google.com/protocol-buffers/docs/encoding#varints).
      sealed abstract class State
      final case class ReadingSize(sizeBytes: Array[Byte]) extends State
      final case class ReadingData(dataBytes: Array[Byte], position: Int)
          extends State
      var state: State = ReadingSize(Array.emptyByteArray)
      byte =>
        state match {
          case ReadingSize(sizeBytes) =>
            val updatedSizeBytes = sizeBytes :+ byte
            if ((byte & 0x80) == 0) {
              val size = CodedInputStream.readRawVarint32(
                updatedSizeBytes.head.toInt,
                new ByteArrayInputStream(updatedSizeBytes.tail)
              )
              state = ReadingData(Array.fill(size)(0), position = 0)
            } else {
              state = ReadingSize(updatedSizeBytes)
            }
            Seq.empty

          case ReadingData(dataBytes, position) =>
            dataBytes(position) = byte
            if (position == dataBytes.length - 1) {
              state = ReadingSize(Array.emptyByteArray)
              Seq(dataBytes)
            } else {
              state = ReadingData(dataBytes, position + 1)
              Seq.empty
            }
        }
    }
    delimitedByteArraySource.map { delimitedByteArray =>
      val protoZoneNotificationMessage =
        proto.ws.protocol.ZoneNotificationMessage.parseFrom(delimitedByteArray)
      val zoneNotification =
        ProtoBinding[ZoneNotification, proto.ws.protocol.ZoneNotification, Any]
          .asScala(protoZoneNotificationMessage.toZoneNotification)(())
      zoneNotification
    }
  }

  private[this] def changeZoneName(zoneId: ZoneId)(
      implicit ec: ExecutionContext): Future[Option[String]] = {
    val changedName = None
    for (zoneResponse <- execZoneCommand(
           zoneId,
           ChangeZoneNameCommand(name = changedName)
         )) yield {
      assert(zoneResponse === ChangeZoneNameResponse(Validated.valid(())))
      changedName
    }
  }

  private[this] def zoneNameChanged(zone: Zone, name: Option[String]): Zone =
    awaitZoneProjection(zone.copy(name = name))

  private[this] def createMember(zoneId: ZoneId)(
      implicit ec: ExecutionContext): Future[Member] =
    for (zoneResponse <- execZoneCommand(
           zoneId,
           CreateMemberCommand(
             ownerPublicKeys = Set(PublicKey(rsaPublicKey.getEncoded)),
             name = Some("Jenny"),
             metadata = None
           )
         ))
      yield
        zoneResponse match {
          case CreateMemberResponse(Validated.Valid(member)) =>
            assert(
              member.ownerPublicKeys === Set(
                PublicKey(rsaPublicKey.getEncoded)))
            assert(member.name === Some("Jenny"))
            assert(member.metadata === None)
            member

          case _ =>
            fail()
        }

  private[this] def memberCreated(zone: Zone, member: Member): Zone =
    awaitZoneProjection(
      zone.copy(
        members = zone.members + (member.id -> member)
      )
    )

  private[this] def updateMember(zoneId: ZoneId, member: Member)(
      implicit ec: ExecutionContext): Future[Member] = {
    val updatedMember = member.copy(name = None)
    for (zoneResponse <- execZoneCommand(
           zoneId,
           UpdateMemberCommand(
             updatedMember
           )
         )) yield {
      assert(zoneResponse === UpdateMemberResponse(Validated.valid(())))
      updatedMember
    }
  }

  private[this] def memberUpdated(zone: Zone, member: Member): Zone =
    awaitZoneProjection(
      zone.copy(
        members = zone.members + (member.id -> member)
      )
    )

  private[this] def createAccount(zoneId: ZoneId, owner: MemberId)(
      implicit ec: ExecutionContext): Future[(Account, BigDecimal)] =
    for (zoneResponse <- execZoneCommand(
           zoneId,
           CreateAccountCommand(
             ownerMemberIds = Set(owner),
             name = Some("Jenny's Account"),
             metadata = None
           )
         ))
      yield
        zoneResponse match {
          case CreateAccountResponse(Validated.Valid(account)) =>
            assert(account.ownerMemberIds === Set(owner))
            assert(account.name === Some("Jenny's Account"))
            assert(account.metadata === None)
            account -> BigDecimal(0)

          case _ =>
            fail()
        }

  private[this] def accountCreated(
      zone: Zone,
      balances: Map[AccountId, BigDecimal],
      account: Account): (Zone, Map[AccountId, BigDecimal]) =
    (
      awaitZoneProjection(
        zone.copy(
          accounts = zone.accounts + (account.id -> account)
        )
      ),
      awaitZoneBalancesProjection(
        zone.id,
        balances + (account.id -> BigDecimal(0))
      )
    )

  private[this] def updateAccount(zoneId: ZoneId, account: Account)(
      implicit ec: ExecutionContext): Future[Account] = {
    val updatedAccount = account.copy(name = None)
    for (zoneResponse <- execZoneCommand(
           zoneId,
           UpdateAccountCommand(
             actingAs = account.ownerMemberIds.head,
             updatedAccount
           )
         )) yield {
      assert(zoneResponse === UpdateAccountResponse(Validated.valid(())))
      updatedAccount
    }
  }

  private[this] def accountUpdated(zone: Zone, account: Account): Zone =
    awaitZoneProjection(
      zone.copy(
        accounts = zone.accounts + (account.id -> account)
      )
    )

  private[this] def addTransaction(zoneId: ZoneId, zone: Zone, to: AccountId)(
      implicit ec: ExecutionContext): Future[Transaction] =
    for (zoneResponse <- execZoneCommand(
           zoneId,
           AddTransactionCommand(
             actingAs = zone.accounts(zone.equityAccountId).ownerMemberIds.head,
             from = zone.equityAccountId,
             to = to,
             value = BigDecimal("5000000000000000000000"),
             description = Some("Jenny's Lottery Win"),
             metadata = None
           )
         ))
      yield
        zoneResponse match {
          case AddTransactionResponse(Validated.Valid(transaction)) =>
            assert(transaction.from === zone.equityAccountId)
            assert(transaction.to === to)
            assert(transaction.value === BigDecimal("5000000000000000000000"))
            assert(
              transaction.creator === zone
                .accounts(zone.equityAccountId)
                .ownerMemberIds
                .head)
            assert(
              transaction.created.toEpochMilli === Spread(
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

  private[this] def transactionAdded(
      zone: Zone,
      balances: Map[AccountId, BigDecimal],
      transaction: Transaction): (Zone, Map[AccountId, BigDecimal]) =
    (
      awaitZoneProjection(
        zone.copy(
          transactions = zone.transactions + (transaction.id -> transaction)
        )
      ),
      awaitZoneBalancesProjection(
        zone.id,
        balances +
          (transaction.from -> (balances(transaction.from) - transaction.value)) +
          (transaction.to -> (balances(transaction.to) + transaction.value))
      )
    )

  private[this] def createZone(createZoneCommand: CreateZoneCommand)(
      implicit ec: ExecutionContext): Future[ZoneResponse] =
    execZoneCommand(
      Uri.Path.Empty,
      ProtoBinding[CreateZoneCommand, proto.ws.protocol.CreateZoneCommand, Any]
        .asProto(createZoneCommand)(())
        .toByteArray
    )

  private[this] def execZoneCommand(zoneId: ZoneId, zoneCommand: ZoneCommand)(
      implicit ec: ExecutionContext): Future[ZoneResponse] =
    execZoneCommand(
      Uri.Path / zoneId.value,
      ProtoBinding[ZoneCommand, proto.ws.protocol.ZoneCommand, Any]
        .asProto(zoneCommand)(())
        .asMessage
        .toByteArray
    )

  private[this] def execZoneCommand(zoneSubPath: Uri.Path, entity: Array[Byte])(
      implicit ec: ExecutionContext): Future[ZoneResponse] =
    for {
      httpResponse <- Http().singleRequest(
        HttpRequest(
          method = HttpMethods.PUT,
          uri = baseUri.withPath(Uri.Path("/zone") ++ zoneSubPath),
          headers = Seq(
            Authorization(OAuth2BearerToken(selfSignedJwt)),
            Accept(
              MediaRange(
                MediaType.customBinary(mainType = "application",
                                       subType = "x-protobuf",
                                       comp = MediaType.NotCompressible)
              )
            )
          ),
          entity = HttpEntity(
            ContentType(
              MediaType.customBinary(mainType = "application",
                                     subType = "x-protobuf",
                                     comp = MediaType.NotCompressible)
            ),
            entity
          )
        ),
        httpsConnectionContext
      )
      _ = assert(httpResponse.status === StatusCodes.OK)
      byteString <- Unmarshal(httpResponse.entity).to[ByteString]
      protoZoneResponseMessage = proto.ws.protocol.ZoneResponseMessage
        .parseFrom(
          byteString.toArray
        )
    } yield
      ProtoBinding[ZoneResponse, proto.ws.protocol.ZoneResponse, Any].asScala(
        protoZoneResponseMessage.toZoneResponse
      )(())

  private[this] def awaitZoneProjection(zone: Zone): Zone = {
    val retrieveAllMembers: ConnectionIO[Seq[(MemberId, Member)]] = {
      def retrieve(memberId: MemberId): ConnectionIO[(MemberId, Member)] = {
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
            .query[PublicKey]
            .to[Set]
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
        } yield memberId -> Member(memberId, ownerPublicKeys, name, metadata)
      }

      for {
        memberIds <- sql"""
         SELECT member_id
           FROM members
           WHERE zone_id = ${zone.id}
        """
          .query[MemberId]
          .to[Vector]
        members <- memberIds
          .map(retrieve)
          .toList
          .sequence
      } yield members
    }
    val retrieveAllAccounts: ConnectionIO[Seq[(AccountId, Account)]] = {
      def retrieve(accountId: AccountId): ConnectionIO[(AccountId, Account)] = {
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
            .query[MemberId]
            .to[Set]
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
        } yield accountId -> Account(accountId, ownerMemberIds, name, metadata)
      }

      for {
        accountIds <- sql"""
         SELECT account_id
           FROM accounts
           WHERE zone_id = ${zone.id}
        """
          .query[AccountId]
          .to[Vector]
        accounts <- accountIds
          .map(retrieve)
          .toList
          .sequence
      } yield accounts
    }
    val retrieveAllTransactions
      : ConnectionIO[Seq[(TransactionId, Transaction)]] =
      sql"""
       SELECT transaction_id, `from`, `to`, `value`, creator, created, description, metadata
         FROM transactions
         WHERE zone_id = ${zone.id}
      """
        .query[(TransactionId,
                AccountId,
                AccountId,
                BigDecimal,
                MemberId,
                Instant,
                Option[String],
                Option[com.google.protobuf.struct.Struct])]
        .to[Vector]
        .map(_.map {
          case values @ (id, _, _, _, _, _, _, _) =>
            id -> Transaction.tupled(values)
        })
    val retrieveOption: ConnectionIO[Option[Zone]] =
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
          .query[(Option[String],
                  AccountId,
                  Instant,
                  Instant,
                  Option[com.google.protobuf.struct.Struct])]
          .option
        maybeZone <- maybeZoneMetadata match {
          case None =>
            None.pure[ConnectionIO]

          case Some((name, equityAccountId, created, expires, metadata)) =>
            for {
              members <- retrieveAllMembers.map(_.toMap)
              accounts <- retrieveAllAccounts.map(_.toMap)
              transactions <- retrieveAllTransactions.map(_.toMap)
            } yield
              Some(
                Zone(zone.id,
                     equityAccountId,
                     members,
                     accounts,
                     transactions,
                     created,
                     expires,
                     name,
                     metadata)
              )
        }
      } yield maybeZone
    eventually {
      assert(
        retrieveOption
          .transact(analyticsTransactor)
          .unsafeRunSync() === Some(zone)
      )
    }
    zone
  }

  private[this] def awaitZoneBalancesProjection(
      zoneId: ZoneId,
      balances: Map[AccountId, BigDecimal]): Map[AccountId, BigDecimal] = {
    val retrieveAll =
      sql"""
        SELECT account_id, balance
          FROM accounts
          WHERE zone_id = $zoneId
      """
        .query[(AccountId, BigDecimal)]
        .to[Vector]
    eventually {
      assert(
        retrieveAll
          .transact(analyticsTransactor)
          .unsafeRunSync()
          .toMap === balances
      )
    }
    balances
  }

  protected[this] def httpsConnectionContext: HttpsConnectionContext

  protected[this] def baseUri: Uri

  protected[this] def analyticsTransactor: Transactor[IO]

  protected[this] def urlFor(authority: String,
                             database: Option[String] = None): String =
    s"jdbc:mysql://$authority${database.fold("")(database => s"/$database")}?" +
      "useSSL=false&" +
      "cacheCallableStmts=true&" +
      "cachePrepStmts=true&" +
      "cacheResultSetMetadata=true&" +
      "cacheServerConfiguration=true&" +
      "useLocalSessionState=true&" +
      "useServerPrepStmts=true"

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }
}

object LiquidityServerSpec {

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
        Json.stringify(
          Json.obj(
            "sub" -> okio.ByteString.of(rsaPublicKey.getEncoded: _*).base64()
          )
        )
      )
    )
    jws.sign(new RSASSASigner(rsaPrivateKey))
    jws.serialize()
  }
}
