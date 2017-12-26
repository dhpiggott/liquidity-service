package com.dhpcs.liquidity.server

import java.io.File
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.security.{KeyPairGenerator, Signature}
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{
  BinaryMessage,
  WebSocketRequest,
  Message => WsMessage
}
import akka.http.scaladsl.model.{HttpRequest, StatusCodes, Uri}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.{Flow, Keep, Source}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import akka.stream.testkit.{TestPublisher, TestSubscriber}
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit.TestKit
import akka.util.ByteString
import cats.data.Validated
import cats.effect.IO
import cats.instances.list._
import cats.syntax.traverse._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.model.ProtoBindings._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.server.LiquidityServerSpec._
import com.dhpcs.liquidity.ws.protocol.ProtoBindings._
import com.dhpcs.liquidity.ws.protocol._
import com.trueaccord.scalapb.json.JsonFormat
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport._
import doobie._
import doobie.implicits._
import org.scalactic.TripleEqualsSupport.Spread
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Inside}
import play.api.libs.json.{JsValue, Json}

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.sys.process.Process
import scala.sys.process.ProcessBuilder

object LiquidityServerSpec {

  private val (rsaPrivateKey: RSAPrivateKey, rsaPublicKey: RSAPublicKey) = {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(2048)
    val keyPair = keyPairGenerator.generateKeyPair
    (keyPair.getPrivate, keyPair.getPublic)
  }

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
      command = Seq("docker-compose",
                    "--project-name",
                    projectName,
                    "--file",
                    new File("docker-compose.yml").getCanonicalPath) ++
        commandArgs,
      cwd = None,
      extraEnv = "TAG" -> BuildInfo.version.replace('+', '-')
    )

  private def execSqlFile(path: String): ConnectionIO[Unit] =
    scala.io.Source
      .fromFile(path)
      .mkString("")
      .split(';')
      .filter(!_.trim.isEmpty)
      .map(Fragment.const0(_).update.run)
      .toList
      .sequence
      .map(_ => ())

}

class LiquidityServerSpec
    extends FreeSpec
    with BeforeAndAfterAll
    with Eventually
    with ScalaFutures
    with IntegrationPatience
    with Inside {

  private[this] val projectName = UUID.randomUUID().toString

  private[this] implicit val system: ActorSystem = ActorSystem()
  private[this] implicit val mat: Materializer = ActorMaterializer()

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    assert(dockerCompose(projectName, "up", "-d", "--remove-orphans").! === 0)
    val (_, mysqlPort) =
      externalDockerComposeServicePorts(projectName, "mysql", 3306).head
    val transactor = Transactor.fromDriverManager[IO](
      driver = "com.mysql.jdbc.Driver",
      url =
        s"jdbc:mysql://localhost:$mysqlPort/?useSSL=false&" +
          "cacheCallableStmts=true&cachePrepStmts=true&" +
          "cacheResultSetMetadata=true&cacheServerConfiguration=true&" +
          "useLocalSessionState=true&useLocalSessionState=true&" +
          "useServerPrepStmts=true",
      user = "root",
      pass = ""
    )
    val connectionTest = for (_ <- sql"SELECT 1".query[Int].unique) yield ()
    eventually(
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
  }

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    assert(dockerCompose(projectName, "down", "--volumes").! === 0)
    super.afterAll()
  }

  "The LiquidityServer" - {
    "forms a cluster" in {
      eventually {
        val (_, akkaManagementPort) =
          externalDockerComposeServicePorts(projectName, "zone-host", 19999).head
        val response = Http()
          .singleRequest(
            HttpRequest(
              uri = Uri(s"http://localhost:$akkaManagementPort/members")
            )
          )
          .futureValue
        assert(response.status === StatusCodes.OK)
        val asJsValue = Unmarshal(response.entity).to[JsValue].futureValue
        val nodeRolesAndStatuses = for {
          member <- (asJsValue \ "members").as[Seq[JsValue]]
          node = (member \ "node").as[String]
          roles = (member \ "roles").as[Seq[String]]
          status = (member \ "status").as[String]
        } yield (node, (roles, status))
        assert(
          nodeRolesAndStatuses.toMap === Map(
            ("akka.tcp://liquidity@zone-host:2552",
             (Seq("zone-host", "dc-default"), "Up")),
            ("akka.tcp://liquidity@client-relay:2552",
             (Seq("client-relay", "dc-default"), "Up")),
            ("akka.tcp://liquidity@analytics:2552",
             (Seq("analytics", "dc-default"), "Up"))
          )
        )
        assert(
          (asJsValue \ "selfNode")
            .as[String] === "akka.tcp://liquidity@zone-host:2552")
        assert((asJsValue \ "unreachable").as[Seq[JsValue]] === Seq.empty)
      }
    }
    "becomes healthy" in {
      val (_, akkaHttpPort) =
        externalDockerComposeServicePorts(projectName, "client-relay", 8080).head
      eventually {
        val response = Http()
          .singleRequest(
            HttpRequest(
              uri = Uri(s"http://localhost:$akkaHttpPort/status")
            )
          )
          .futureValue
        assert(response.status === StatusCodes.OK)
        val asJsValue = Unmarshal(response.entity).to[JsValue].futureValue
        assert(
          asJsValue === Json.parse(
            """
              |{
              |  "activeClients" : {
              |    "count" : 0,
              |    "publicKeyFingerprints" : [ ]
              |  },
              |  "activeZones" : {
              |    "count" : 0,
              |    "zones" : [ ]
              |  },
              |  "totals" : {
              |    "zones" : 0,
              |    "publicKeys" : 0,
              |    "members" : 0,
              |    "accounts" : 0,
              |    "transactions" : 0
              |  }
              |}
            """.stripMargin
          ))
      }
    }
    "accepts and projects create zone commands" in withWsTestProbes {
      (sub, pub) =>
        val (createdZone, createdBalances) = createZone(sub, pub)
        zoneCreated(createdZone, createdBalances)
        ()
    }
    "accepts join zone commands" in withWsTestProbes { (sub, pub) =>
      val (createdZone, createdBalances) = createZone(sub, pub)
      zoneCreated(createdZone, createdBalances)
      joinZone(sub, pub, createdZone.id, createdZone)
      ()
    }
    "accepts quit zone commands" in withWsTestProbes { (sub, pub) =>
      val (createdZone, createdBalances) = createZone(sub, pub)
      zoneCreated(createdZone, createdBalances)
      joinZone(sub, pub, createdZone.id, createdZone)
      quitZone(sub, pub, createdZone.id)
      ()
    }
    "accepts and projects change zone name commands" in withWsTestProbes {
      (sub, pub) =>
        val (createdZone, createdBalances) = createZone(sub, pub)
        zoneCreated(createdZone, createdBalances)
        val changedName = changeZoneName(sub, pub, createdZone.id)
        zoneNameChanged(createdZone, changedName)
        ()
    }
    "accepts and projects create member commands" in withWsTestProbes {
      (sub, pub) =>
        val (createdZone, createdBalances) = createZone(sub, pub)
        zoneCreated(createdZone, createdBalances)
        val createdMember = createMember(sub, pub, createdZone.id)
        memberCreated(createdZone, createdMember)
        ()
    }
    "accepts and projects update member commands" in withWsTestProbes {
      (sub, pub) =>
        val (createdZone, createdBalances) = createZone(sub, pub)
        zoneCreated(createdZone, createdBalances)
        val createdMember = createMember(sub, pub, createdZone.id)
        val zoneWithCreatedMember = memberCreated(createdZone, createdMember)
        val updatedMember =
          updateMember(sub, pub, zoneWithCreatedMember.id, createdMember)
        memberUpdated(zoneWithCreatedMember, updatedMember)
        ()
    }
    "accepts and projects create account commands" in withWsTestProbes {
      (sub, pub) =>
        val (createdZone, createdBalances) = createZone(sub, pub)
        zoneCreated(createdZone, createdBalances)
        val createdMember = createMember(sub, pub, createdZone.id)
        val zoneWithCreatedMember = memberCreated(createdZone, createdMember)
        val (createdAccount, _) =
          createAccount(sub,
                        pub,
                        zoneWithCreatedMember.id,
                        owner = createdMember.id)
        accountCreated(zoneWithCreatedMember, createdBalances, createdAccount)
        ()
    }
    "accepts and projects update account commands" in withWsTestProbes {
      (sub, pub) =>
        val (createdZone, createdBalances) = createZone(sub, pub)
        zoneCreated(createdZone, createdBalances)
        val createdMember = createMember(sub, pub, createdZone.id)
        val zoneWithCreatedMember = memberCreated(createdZone, createdMember)
        val (createdAccount, _) =
          createAccount(sub,
                        pub,
                        zoneWithCreatedMember.id,
                        owner = createdMember.id)
        val (zoneWithCreatedAccount, _) =
          accountCreated(zoneWithCreatedMember, createdBalances, createdAccount)
        val updatedAccount =
          updateAccount(sub, pub, zoneWithCreatedAccount.id, createdAccount)
        accountUpdated(zoneWithCreatedAccount, updatedAccount)
        ()
    }
    "accepts and projects add transaction commands" in withWsTestProbes {
      (sub, pub) =>
        val (createdZone, createdBalances) = createZone(sub, pub)
        zoneCreated(createdZone, createdBalances)
        val createdMember = createMember(sub, pub, createdZone.id)
        val zoneWithCreatedMember = memberCreated(createdZone, createdMember)
        val (createdAccount, _) = createAccount(sub,
                                                pub,
                                                zoneWithCreatedMember.id,
                                                owner = createdMember.id)
        val (zoneWithCreatedAccount, updatedBalances) =
          accountCreated(zoneWithCreatedMember, createdBalances, createdAccount)
        val addedTransaction = addTransaction(sub,
                                              pub,
                                              zoneWithCreatedAccount.id,
                                              zoneWithCreatedAccount,
                                              to = createdAccount.id)
        transactionAdded(zoneWithCreatedAccount,
                         updatedBalances,
                         addedTransaction)
        ()
    }
    "sends PingCommands when left idle" in withWsTestProbes { (sub, _) =>
      sub.within(35.seconds)(
        assert(
          expectCommand(sub) === proto.ws.protocol.ClientMessage.Command.Command
            .PingCommand(com.google.protobuf.ByteString.EMPTY))
      )
      ()
    }
  }

  private[this] def withWsTestProbes(
      test: (TestSubscriber.Probe[proto.ws.protocol.ClientMessage],
             TestPublisher.Probe[proto.ws.protocol.ServerMessage]) => Unit)
    : Unit = {
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
    val testProbeFlow = Flow.fromSinkAndSourceMat(
      TestSink.probe[proto.ws.protocol.ClientMessage],
      TestSource.probe[proto.ws.protocol.ServerMessage]
    )(Keep.both)
    val wsClientFlow =
      InFlow
        .viaMat(testProbeFlow)(Keep.right)
        .via(OutFlow)
    val (_, akkaHttpPort) =
      externalDockerComposeServicePorts(projectName, "client-relay", 8080).head
    val (_, (sub, pub)) = Http().singleWebSocketRequest(
      WebSocketRequest(s"ws://localhost:$akkaHttpPort/ws"),
      wsClientFlow
    )
    val keyOwnershipChallenge = inside(expectMessage(sub)) {
      case keyOwnershipChallenge: proto.ws.protocol.ClientMessage.Message.KeyOwnershipChallenge =>
        keyOwnershipChallenge.value
    }
    sendMessage(pub)(
      proto.ws.protocol.ServerMessage.Message
        .KeyOwnershipProof(
          createKeyOwnershipProof(
            rsaPublicKey,
            rsaPrivateKey,
            keyOwnershipChallenge
          )
        )
    )
    try test(sub, pub)
    finally { pub.sendComplete(); () }
  }

  private final val InFlow
    : Flow[WsMessage, proto.ws.protocol.ClientMessage, NotUsed] =
    Flow[WsMessage].flatMapConcat(
      wsMessage =>
        for (byteString <- wsMessage.asBinaryMessage match {
               case BinaryMessage.Streamed(dataStream) =>
                 dataStream.fold(ByteString.empty)((acc, data) => acc ++ data)
               case BinaryMessage.Strict(data) => Source.single(data)
             })
          yield proto.ws.protocol.ClientMessage.parseFrom(byteString.toArray))

  private final val OutFlow
    : Flow[proto.ws.protocol.ServerMessage, WsMessage, NotUsed] =
    Flow[proto.ws.protocol.ServerMessage].map(
      serverMessage => BinaryMessage(ByteString(serverMessage.toByteArray))
    )

  private[this] def createZone(
      sub: TestSubscriber.Probe[proto.ws.protocol.ClientMessage],
      pub: TestPublisher.Probe[proto.ws.protocol.ServerMessage])
    : (Zone, Map[AccountId, BigDecimal]) = {
    sendCreateZoneCommand(pub)(
      CreateZoneCommand(
        equityOwnerPublicKey = PublicKey(rsaPublicKey.getEncoded),
        equityOwnerName = Some("Dave"),
        equityOwnerMetadata = None,
        equityAccountName = None,
        equityAccountMetadata = None,
        name = Some("Dave's Game"),
        metadata = None
      ),
      correlationId = 0L
    )
    inside(expectZoneResponse(sub, expectedCorrelationId = 0L)) {
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
          zone.created === Spread(
            pivot = Instant.now().toEpochMilli,
            tolerance = 5000L
          ))
        assert(
          zone.expires === Spread(
            pivot = Instant.now().plus(7, ChronoUnit.DAYS).toEpochMilli,
            tolerance = 5000L
          )
        )
        assert(zone.transactions === Map.empty)
        assert(zone.name === Some("Dave's Game"))
        assert(zone.metadata === None)
        (
          zone,
          Map(zone.equityAccountId -> BigDecimal(0))
        )
    }
  }

  private[this] def zoneCreated(zone: Zone,
                                balances: Map[AccountId, BigDecimal])
    : (Zone, Map[AccountId, BigDecimal]) =
    (
      awaitZoneProjection(zone),
      awaitZoneBalancesProjection(zone.id, balances)
    )

  private[this] def joinZone(
      sub: TestSubscriber.Probe[proto.ws.protocol.ClientMessage],
      pub: TestPublisher.Probe[proto.ws.protocol.ServerMessage],
      zoneId: ZoneId,
      zone: Zone): Unit = {
    sendZoneCommand(pub)(
      zoneId,
      JoinZoneCommand,
      correlationId = 0L
    )
    inside(expectZoneResponse(sub, expectedCorrelationId = 0L)) {
      case JoinZoneResponse(Validated.Valid(zoneAndConnectedClients)) =>
        val (_zone, _connectedClients) = zoneAndConnectedClients
        assert(_zone === zone)
        assert(
          _connectedClients.values.toSet ===
            Set(PublicKey(rsaPublicKey.getEncoded))
        )
    }
    inside(expectZoneNotification(sub)) {
      case ClientJoinedNotification(_, publicKey) =>
        assert(publicKey === PublicKey(rsaPublicKey.getEncoded))
    }
    ()
  }

  private[this] def quitZone(
      sub: TestSubscriber.Probe[proto.ws.protocol.ClientMessage],
      pub: TestPublisher.Probe[proto.ws.protocol.ServerMessage],
      zoneId: ZoneId): Unit = {
    sendZoneCommand(pub)(
      zoneId,
      QuitZoneCommand,
      correlationId = 0L
    )
    assert(
      expectZoneResponse(sub, expectedCorrelationId = 0L) === QuitZoneResponse(
        Validated.valid(())))
    ()
  }

  private[this] def changeZoneName(
      sub: TestSubscriber.Probe[proto.ws.protocol.ClientMessage],
      pub: TestPublisher.Probe[proto.ws.protocol.ServerMessage],
      zoneId: ZoneId): Option[String] = {
    val changedName = None
    sendZoneCommand(pub)(
      zoneId,
      ChangeZoneNameCommand(name = changedName),
      correlationId = 0L
    )
    assert(
      expectZoneResponse(sub, expectedCorrelationId = 0L) === ChangeZoneNameResponse(
        Validated.valid(())))
    changedName
  }

  private[this] def zoneNameChanged(zone: Zone, name: Option[String]): Zone =
    awaitZoneProjection(zone.copy(name = name))

  private[this] def createMember(
      sub: TestSubscriber.Probe[proto.ws.protocol.ClientMessage],
      pub: TestPublisher.Probe[proto.ws.protocol.ServerMessage],
      zoneId: ZoneId): Member = {
    sendZoneCommand(pub)(
      zoneId,
      CreateMemberCommand(
        ownerPublicKeys = Set(PublicKey(rsaPublicKey.getEncoded)),
        name = Some("Jenny"),
        metadata = None
      ),
      correlationId = 0L
    )
    inside(expectZoneResponse(sub, expectedCorrelationId = 0L)) {
      case CreateMemberResponse(Validated.Valid(member)) =>
        assert(
          member.ownerPublicKeys === Set(PublicKey(rsaPublicKey.getEncoded)))
        assert(member.name === Some("Jenny"))
        assert(member.metadata === None)
        member
    }
  }

  private[this] def memberCreated(zone: Zone, member: Member): Zone =
    awaitZoneProjection(
      zone.copy(
        members = zone.members + (member.id -> member)
      )
    )

  private[this] def updateMember(
      sub: TestSubscriber.Probe[proto.ws.protocol.ClientMessage],
      pub: TestPublisher.Probe[proto.ws.protocol.ServerMessage],
      zoneId: ZoneId,
      member: Member): Member = {
    val updatedMember = member.copy(name = None)
    sendZoneCommand(pub)(
      zoneId,
      UpdateMemberCommand(
        updatedMember
      ),
      correlationId = 0L
    )
    assert(
      expectZoneResponse(sub, expectedCorrelationId = 0L) === UpdateMemberResponse(
        Validated.valid(()))
    )
    updatedMember
  }

  private[this] def memberUpdated(zone: Zone, member: Member): Zone =
    awaitZoneProjection(
      zone.copy(
        members = zone.members + (member.id -> member)
      )
    )

  private[this] def createAccount(
      sub: TestSubscriber.Probe[proto.ws.protocol.ClientMessage],
      pub: TestPublisher.Probe[proto.ws.protocol.ServerMessage],
      zoneId: ZoneId,
      owner: MemberId
  ): (Account, BigDecimal) = {
    sendZoneCommand(pub)(
      zoneId,
      CreateAccountCommand(
        ownerMemberIds = Set(owner),
        name = Some("Jenny's Account"),
        metadata = None
      ),
      correlationId = 0L
    )
    inside(expectZoneResponse(sub, expectedCorrelationId = 0L)) {
      case CreateAccountResponse(Validated.Valid(account)) =>
        assert(account.ownerMemberIds === Set(owner))
        assert(account.name === Some("Jenny's Account"))
        account -> BigDecimal(0)
    }
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

  private[this] def updateAccount(
      sub: TestSubscriber.Probe[proto.ws.protocol.ClientMessage],
      pub: TestPublisher.Probe[proto.ws.protocol.ServerMessage],
      zoneId: ZoneId,
      account: Account): Account = {
    val updatedAccount = account.copy(name = None)
    sendZoneCommand(pub)(
      zoneId,
      UpdateAccountCommand(
        actingAs = account.ownerMemberIds.head,
        updatedAccount
      ),
      correlationId = 0L
    )
    assert(
      expectZoneResponse(sub, expectedCorrelationId = 0L) === UpdateAccountResponse(
        Validated.valid(())))
    updatedAccount
  }

  private[this] def accountUpdated(zone: Zone, account: Account): Zone =
    awaitZoneProjection(
      zone.copy(
        accounts = zone.accounts + (account.id -> account)
      )
    )

  private[this] def addTransaction(
      sub: TestSubscriber.Probe[proto.ws.protocol.ClientMessage],
      pub: TestPublisher.Probe[proto.ws.protocol.ServerMessage],
      zoneId: ZoneId,
      zone: Zone,
      to: AccountId): Transaction = {
    sendZoneCommand(pub)(
      zoneId,
      AddTransactionCommand(
        actingAs = zone.accounts(zone.equityAccountId).ownerMemberIds.head,
        from = zone.equityAccountId,
        to = to,
        value = BigDecimal(5000),
        description = Some("Jenny's Lottery Win"),
        metadata = None
      ),
      correlationId = 0L
    )
    inside(expectZoneResponse(sub, expectedCorrelationId = 0L)) {
      case AddTransactionResponse(Validated.Valid(transaction)) =>
        assert(transaction.from === zone.equityAccountId)
        assert(transaction.to === to)
        assert(transaction.value === BigDecimal(5000))
        assert(
          transaction.creator === zone
            .accounts(zone.equityAccountId)
            .ownerMemberIds
            .head)
        assert(
          transaction.created === Spread(pivot = Instant.now().toEpochMilli,
                                         tolerance = 5000L))
        assert(transaction.description === Some("Jenny's Lottery Win"))
        assert(transaction.metadata === None)
        transaction
    }
  }

  private[this] def transactionAdded(
      zone: Zone,
      balances: Map[AccountId, BigDecimal],
      transaction: Transaction): (Zone, Map[AccountId, BigDecimal]) = {
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
  }

  private[this] def expectCommand(
      sub: TestSubscriber.Probe[proto.ws.protocol.ClientMessage])
    : proto.ws.protocol.ClientMessage.Command.Command =
    inside(sub.requestNext().message) {
      case proto.ws.protocol.ClientMessage.Message.Command(
          proto.ws.protocol.ClientMessage.Command(_, protoCommand)
          ) =>
        protoCommand
    }

  private[this] def sendCreateZoneCommand(
      pub: TestPublisher.Probe[proto.ws.protocol.ServerMessage])(
      createZoneCommand: CreateZoneCommand,
      correlationId: Long): Unit =
    sendMessage(pub)(
      proto.ws.protocol.ServerMessage.Message.Command(
        proto.ws.protocol.ServerMessage.Command(
          correlationId,
          proto.ws.protocol.ServerMessage.Command.Command.CreateZoneCommand(
            ProtoBinding[CreateZoneCommand,
                         proto.ws.protocol.ZoneCommand.CreateZoneCommand,
                         Any]
              .asProto(createZoneCommand)(())
          )
        )))

  private[this] def sendZoneCommand(
      pub: TestPublisher.Probe[proto.ws.protocol.ServerMessage])(
      zoneId: ZoneId,
      zoneCommand: ZoneCommand,
      correlationId: Long): Unit =
    sendMessage(pub)(
      proto.ws.protocol.ServerMessage.Message.Command(
        proto.ws.protocol.ServerMessage.Command(
          correlationId,
          proto.ws.protocol.ServerMessage.Command.Command.ZoneCommandEnvelope(
            proto.ws.protocol.ServerMessage.Command.ZoneCommandEnvelope(
              zoneId.value.toString,
              Some(
                ProtoBinding[ZoneCommand, proto.ws.protocol.ZoneCommand, Any]
                  .asProto(zoneCommand)(())
              )))
        )))

  private[this] def sendMessage(
      pub: TestPublisher.Probe[proto.ws.protocol.ServerMessage])(
      message: proto.ws.protocol.ServerMessage.Message): Unit = {
    pub.sendNext(
      proto.ws.protocol.ServerMessage(message)
    )
    ()
  }

  private[this] def expectZoneResponse(
      sub: TestSubscriber.Probe[proto.ws.protocol.ClientMessage],
      expectedCorrelationId: Long): ZoneResponse =
    inside(expectMessage(sub)) {
      case proto.ws.protocol.ClientMessage.Message.Response(protoResponse) =>
        assert(protoResponse.correlationId == expectedCorrelationId)
        inside(protoResponse.response) {
          case proto.ws.protocol.ClientMessage.Response.Response
                .ZoneResponse(protoZoneResponse) =>
            ProtoBinding[ZoneResponse, proto.ws.protocol.ZoneResponse, Any]
              .asScala(protoZoneResponse)(())
        }
    }

  private[this] def expectZoneNotification(
      sub: TestSubscriber.Probe[proto.ws.protocol.ClientMessage])
    : ZoneNotification =
    inside(expectMessage(sub)) {
      case proto.ws.protocol.ClientMessage.Message
            .Notification(protoNotification) =>
        inside(protoNotification.notification) {
          case proto.ws.protocol.ClientMessage.Notification.Notification
                .ZoneNotificationEnvelope(
                proto.ws.protocol.ClientMessage.Notification
                  .ZoneNotificationEnvelope(_, Some(protoZoneNotification))) =>
            ProtoBinding[ZoneNotification,
                         proto.ws.protocol.ZoneNotification,
                         Any]
              .asScala(protoZoneNotification)(())
        }
    }

  private[this] def expectMessage(
      sub: TestSubscriber.Probe[proto.ws.protocol.ClientMessage])
    : proto.ws.protocol.ClientMessage.Message =
    sub.requestNext().message

  private[this] def awaitZoneProjection(zone: Zone): Zone = {
    val (_, akkaHttpPort) =
      externalDockerComposeServicePorts(projectName, "client-relay", 8080).head
    eventually {
      val response = Http()
        .singleRequest(
          HttpRequest(
            uri = Uri(
              s"http://localhost:$akkaHttpPort/analytics/zones/${zone.id.value}")
          )
        )
        .futureValue
      assert(response.status === StatusCodes.OK)
      assert(
        Unmarshal(response.entity).to[JsValue].futureValue ===
          Json.parse(
            JsonFormat.toJsonString(
              ProtoBinding[Zone, proto.model.Zone, Any].asProto(zone)(())
            )
          )
      )
    }
    zone
  }

  private[this] def awaitZoneBalancesProjection(
      zoneId: ZoneId,
      balances: Map[AccountId, BigDecimal]): Map[AccountId, BigDecimal] = {
    val (_, akkaHttpPort) =
      externalDockerComposeServicePorts(projectName, "client-relay", 8080).head
    eventually {
      val response = Http()
        .singleRequest(
          HttpRequest(
            uri = Uri(
              s"http://localhost:$akkaHttpPort/analytics/zones/${zoneId.value}/balances")
          )
        )
        .futureValue
      assert(response.status === StatusCodes.OK)
      assert(
        Unmarshal(response.entity)
          .to[JsValue]
          .futureValue
          .as[Map[String, BigDecimal]] === balances.map {
          case (accountId, balance) => accountId.value -> balance
        })
    }
    balances
  }

}
