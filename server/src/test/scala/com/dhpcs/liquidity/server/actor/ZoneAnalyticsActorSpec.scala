package com.dhpcs.liquidity.server.actor

import java.net.InetAddress
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.UUID

import akka.actor.typed.ActorRefResolver
import akka.actor.typed.scaladsl.adapter._
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.persistence.PersistentActor
import akka.persistence.inmemory.extension._
import akka.persistence.inmemory.query.scaladsl.InMemoryReadJournal
import akka.persistence.journal.Tagged
import akka.persistence.query.PersistenceQuery
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import cats.effect.IO
import cats.instances.list._
import cats.syntax.applicative._
import cats.syntax.traverse._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.persistence.EventTags
import com.dhpcs.liquidity.persistence.zone._
import com.dhpcs.liquidity.server.SqlBindings._
import com.dhpcs.liquidity.server.actor.ZoneAnalyticsActorSpec._
import com.typesafe.config.ConfigFactory
import doobie._
import doobie.implicits._
import org.h2.tools.Server
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import org.scalatest.{BeforeAndAfterAll, Outcome, fixture}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

class ZoneAnalyticsActorSpec
    extends fixture.FreeSpec
    with BeforeAndAfterAll
    with Eventually
    with IntegrationPatience
    with ScalaFutures {

  "ZoneAnalyticsActor" - {
    "projects zone created events" in { fixture =>
      zoneCreated(fixture)
    }
    "projects client joined events" in { fixture =>
      zoneCreated(fixture)
      val clientConnection = TestProbe().ref
      clientJoined(fixture, clientConnection)
    }
    "projects client quit events" in { fixture =>
      zoneCreated(fixture)
      val clientConnection = TestProbe().ref
      val joined = clientJoined(fixture, clientConnection)
      clientQuit(fixture, clientConnection, joined)
    }
    "projects zone name changed events" in { fixture =>
      zoneCreated(fixture)
      zoneNameChanged(fixture)
    }
    "projects member created events" in { fixture =>
      zoneCreated(fixture)
      memberCreated(fixture)
    }
    "projects member updated events" in { fixture =>
      zoneCreated(fixture)
      val member = memberCreated(fixture)
      memberUpdated(fixture, member)
    }
    "projects account created events" in { fixture =>
      zoneCreated(fixture)
      val member = memberCreated(fixture)
      accountCreated(fixture, owner = member.id)
    }
    "projects account updated events" in { fixture =>
      zoneCreated(fixture)
      val member = memberCreated(fixture)
      val account = accountCreated(fixture, owner = member.id)
      accountUpdated(fixture, account)
    }
    "projects transaction added events" in { fixture =>
      val zone = zoneCreated(fixture)
      val member = memberCreated(fixture)
      val account = accountCreated(fixture, owner = member.id)
      transactionAdded(fixture, zone, to = account.id)
    }
  }

  private[this] val config = ConfigFactory.parseString(s"""
       |akka {
       |  loglevel = "WARNING"
       |  actor {
       |    serializers {
       |      zone-record = "com.dhpcs.liquidity.server.serialization.ZoneRecordSerializer"
       |    }
       |    serialization-bindings {
       |      "com.dhpcs.liquidity.persistence.zone.ZoneRecord" = zone-record
       |    }
       |    allow-java-serialization = off
       |  }
       |  persistence {
       |    journal.plugin = "inmemory-journal"
       |    snapshot-store.plugin = "inmemory-snapshot-store"
       |  }
       |}
     """.stripMargin)

  private[this] implicit val system: ActorSystem =
    ActorSystem("zoneAnalyticsActorSpec", config)
  private[this] implicit val mat: Materializer = ActorMaterializer()

  private[this] val readJournal = PersistenceQuery(system)
    .readJournalFor[InMemoryReadJournal](InMemoryReadJournal.Identifier)

  private[this] val h2Server = Server.createTcpServer()

  override def beforeAll(): Unit = {
    super.beforeAll()
    h2Server.start()
    ()
  }

  override protected type FixtureParam = ZoneAnalyticsActorSpec.FixtureParam

  override protected def withFixture(test: OneArgTest): Outcome = {
    val transactor = Transactor.fromDriverManager[IO](
      driver = "org.h2.Driver",
      url =
        s"jdbc:h2:mem:liquidity_analytics_${UUID.randomUUID()};" +
          "DATABASE_TO_UPPER=false;" +
          "MODE=MYSQL;" +
          "DB_CLOSE_DELAY=-1",
      user = "sa",
      pass = ""
    )
    initAnalytics.transact(transactor).unsafeRunSync()
    val analytics = system.spawn(
      ZoneAnalyticsActor.singletonBehavior(readJournal,
                                           transactor,
                                           blockingIoEc =
                                             ExecutionContext.global),
      name = "zoneAnalytics"
    )
    val zoneId = ZoneId(UUID.randomUUID().toString)
    val writer =
      system.actorOf(Props(classOf[WriterActor], zoneId.persistenceId))
    try withFixture(
      test.toNoArgTest((transactor, zoneId, writer))
    )
    finally {
      system.stop(writer)
      system.stop(analytics.toUntyped)
      implicit val askTimeout: Timeout = Timeout(5.seconds)
      Await.result(
        StorageExtension(system).journalStorage ? InMemoryJournalStorage.ClearJournal,
        askTimeout.duration
      )
      ()
    }
  }

  private[this] def zoneCreated(fixture: FixtureParam): Zone = {
    val (transactor, zoneId, _) = fixture
    val created = Instant.now()
    val equityAccountId = AccountId(0.toString)
    val equityAccountOwnerId = MemberId(0.toString)
    val zone = Zone(
      id = zoneId,
      equityAccountId,
      members = Map(
        equityAccountOwnerId -> Member(
          equityAccountOwnerId,
          ownerPublicKeys = Set(publicKey),
          name = Some("Dave"),
          metadata = None
        )
      ),
      accounts = Map(
        equityAccountId -> Account(
          equityAccountId,
          ownerMemberIds = Set(equityAccountOwnerId),
          name = None,
          metadata = None
        )
      ),
      transactions = Map.empty,
      created = created,
      expires = created.plus(java.time.Duration.ofDays(30)),
      name = Some("Dave's Game"),
      metadata = None
    )
    writeEvent(fixture, ZoneCreatedEvent(zone))
    eventually {
      val zoneCount =
        sql"""
           SELECT COUNT(*)
             FROM zones
         """
          .query[Long]
          .unique
          .transact(transactor)
          .unsafeRunSync()
      assert(zoneCount === 1)
      val maybeZone =
        retrieveZoneOption(zoneId)
          .transact(transactor)
          .unsafeRunSync()
      assert(maybeZone === Some(zone))
    }
    zone
  }

  private[this] def clientJoined(fixture: FixtureParam,
                                 clientConnection: ActorRef): Instant = {
    val (transactor, zoneId, _) = fixture
    val actorRef =
      ActorRefResolver(system.toTyped).toSerializationFormat(clientConnection)
    val timestamp = Instant.now()
    writeEvent(fixture, ClientJoinedEvent(Some(actorRef)), timestamp)
    eventually {
      val (_, clientSession) =
        retrieveAllClientSessions(zoneId)
          .transact(transactor)
          .unsafeRunSync()
          .head
      assert(
        clientSession === ClientSession(
          id = clientSession.id,
          remoteAddress = Some(remoteAddress),
          actorRef = actorRef,
          publicKey = publicKey,
          joined = timestamp,
          quit = None
        ))
    }
    timestamp
  }

  private[this] def clientQuit(fixture: FixtureParam,
                               clientConnection: ActorRef,
                               joined: Instant): Unit = {
    val (transactor, zoneId, _) = fixture
    val actorRef =
      ActorRefResolver(system.toTyped).toSerializationFormat(clientConnection)
    val timestamp = Instant.now()
    writeEvent(fixture, ClientQuitEvent(Some(actorRef)), timestamp)
    eventually {
      val (_, clientSession) =
        retrieveAllClientSessions(zoneId)
          .transact(transactor)
          .unsafeRunSync()
          .head
      assert(
        clientSession === ClientSession(
          id = clientSession.id,
          remoteAddress = Some(remoteAddress),
          actorRef = actorRef,
          publicKey = publicKey,
          joined = joined,
          quit = Some(timestamp)
        ))
    }
    ()
  }

  private[this] def zoneNameChanged(fixture: FixtureParam): Unit = {
    val (transactor, zoneId, _) = fixture
    writeEvent(fixture, ZoneNameChangedEvent(name = None))
    eventually {
      val maybeZone =
        retrieveZoneOption(zoneId).transact(transactor).unsafeRunSync()
      assert(maybeZone.exists(_.name.isEmpty))
    }
    ()
  }

  private[this] def memberCreated(fixture: FixtureParam): Member = {
    val (transactor, zoneId, _) = fixture
    val member = Member(
      id = MemberId(1.toString),
      ownerPublicKeys = Set(publicKey),
      name = Some("Jenny"),
      metadata = None
    )
    writeEvent(fixture, MemberCreatedEvent(member))
    eventually {
      val membersCount =
        sql"""
           SELECT COUNT(*)
             FROM members
         """
          .query[Long]
          .unique
          .transact(transactor)
          .unsafeRunSync()
      assert(membersCount === 2)
      val memberOwnersCount =
        sql"""
             SELECT COUNT(DISTINCT fingerprint)
               FROM member_owners
           """
          .query[Long]
          .unique
          .transact(transactor)
          .unsafeRunSync()
      assert(memberOwnersCount === 1)
      val members =
        retrieveAllMembers(zoneId)
          .map(_.toMap)
          .transact(transactor)
          .unsafeRunSync()
      assert(members(member.id) === member)
    }
    member
  }

  private[this] def memberUpdated(fixture: FixtureParam,
                                  member: Member): Unit = {
    val (transactor, zoneId, _) = fixture
    writeEvent(fixture, MemberUpdatedEvent(member.copy(name = None)))
    eventually {
      val members =
        retrieveAllMembers(zoneId)
          .map(_.toMap)
          .transact(transactor)
          .unsafeRunSync()
      assert(members(member.id) === member.copy(name = None))
    }
    ()
  }

  private[this] def accountCreated(fixture: FixtureParam,
                                   owner: MemberId): Account = {
    val (transactor, zoneId, _) = fixture
    val account = Account(
      id = AccountId(1.toString),
      ownerMemberIds = Set(owner),
      name = Some("Jenny's Account"),
      metadata = None
    )
    writeEvent(fixture, AccountCreatedEvent(account))
    eventually {
      val accountsCount =
        sql"""
           SELECT COUNT(*)
             FROM accounts
         """
          .query[Long]
          .unique
          .transact(transactor)
          .unsafeRunSync()
      assert(accountsCount === 2)
      val accounts =
        retrieveAllAccounts(zoneId)
          .map(_.toMap)
          .transact(transactor)
          .unsafeRunSync()
      assert(accounts(account.id) === account)
    }
    account
  }

  private[this] def accountUpdated(fixture: FixtureParam,
                                   account: Account): Unit = {
    val (transactor, zoneId, _) = fixture
    writeEvent(fixture,
               AccountUpdatedEvent(actingAs = Some(account.ownerMemberIds.head),
                                   account.copy(name = None)))
    eventually {
      val accounts =
        retrieveAllAccounts(zoneId)
          .map(_.toMap)
          .transact(transactor)
          .unsafeRunSync()
      assert(accounts(account.id) === account.copy(name = None))
    }
    ()
  }

  private[this] def transactionAdded(fixture: FixtureParam,
                                     zone: Zone,
                                     to: AccountId): Unit = {
    val (transactor, zoneId, _) = fixture
    val transaction = Transaction(
      id = TransactionId(0.toString),
      from = zone.equityAccountId,
      to = to,
      value = BigDecimal(5000),
      creator = zone.accounts(zone.equityAccountId).ownerMemberIds.head,
      created = Instant.now(),
      description = Some("Jenny's Lottery Win"),
      metadata = None
    )
    writeEvent(fixture, TransactionAddedEvent(transaction))
    eventually {
      val transactionCount =
        sql"""
           SELECT COUNT(*)
             FROM transactions
         """
          .query[Long]
          .unique
          .transact(transactor)
          .unsafeRunSync()
      assert(transactionCount === 1)
      val transactions =
        retrieveAllTransactions(zoneId)
          .map(_.toMap)
          .transact(transactor)
          .unsafeRunSync()
      assert(transactions(transaction.id) === transaction)
      val sourceBalance = sql"""
         SELECT balance FROM accounts
           WHERE zone_id = $zoneId AND account_id = ${zone.equityAccountId}
        """
        .query[BigDecimal]
        .unique
        .transact(transactor)
        .unsafeRunSync()
      assert(sourceBalance === BigDecimal(-5000))
      val destinationBalance = sql"""
         SELECT balance FROM accounts
           WHERE zone_id = $zoneId AND account_id = $to
        """
        .query[BigDecimal]
        .unique
        .transact(transactor)
        .unsafeRunSync()
      assert(destinationBalance === BigDecimal(5000))
    }
    ()
  }

  override def afterAll(): Unit = {
    h2Server.stop()
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }
}

object ZoneAnalyticsActorSpec {

  private type FixtureParam = (Transactor[IO], ZoneId, ActorRef)

  private val remoteAddress = InetAddress.getLoopbackAddress
  private val publicKey = {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(2048)
    val keyPair = keyPairGenerator.generateKeyPair
    PublicKey(keyPair.getPublic.getEncoded)
  }

  private val initAnalytics: ConnectionIO[Unit] = for {
    _ <- sql"""
           CREATE TABLE devices (
             public_key BLOB NOT NULL,
             fingerprint CHAR(64) NOT NULL,
             created TIMESTAMP NOT NULL,
             PRIMARY KEY (fingerprint)
           );
         """.update.run
    _ <- sql"""
           CREATE TABLE zones (
             zone_id VARCHAR(36) NOT NULL,
             modified TIMESTAMP NULL,
             equity_account_id VARCHAR(36) NOT NULL,
             created TIMESTAMP NOT NULL,
             expires TIMESTAMP NOT NULL,
             metadata VARCHAR(1024) NULL,
             PRIMARY KEY (zone_id)
           );
         """.update.run
    _ <- sql"""
           CREATE TABLE zone_name_changes (
             zone_id VARCHAR(36) NOT NULL,
             change_id INT NOT NULL AUTO_INCREMENT,
             changed TIMESTAMP NOT NULL,
             name VARCHAR(160) NULL,
             PRIMARY KEY (change_id),
             FOREIGN KEY (zone_id) REFERENCES zones(zone_id)
           );
    """.update.run
    _ <- sql"""
           CREATE TABLE members (
             zone_id CHAR(36) NOT NULL,
             member_id VARCHAR(36) NOT NULL,
             created TIMESTAMP NOT NULL,
             PRIMARY KEY (zone_id, member_id),
             FOREIGN KEY (zone_id) REFERENCES zones(zone_id)
           );
         """.update.run
    _ <- sql"""
           CREATE TABLE member_updates (
             zone_id CHAR(36) NOT NULL,
             member_id VARCHAR(36) NOT NULL,
             update_id INT NOT NULL AUTO_INCREMENT,
             updated TIMESTAMP NOT NULL,
             name VARCHAR(160) NULL,
             metadata VARCHAR(1024) NULL,
             PRIMARY KEY (update_id),
             FOREIGN KEY (zone_id) REFERENCES zones(zone_id),
             FOREIGN KEY (zone_id, member_id) REFERENCES members(zone_id, member_id)
           );
         """.update.run
    _ <- sql"""
           CREATE TABLE member_owners (
             update_id INT NOT NULL,
             fingerprint CHAR(64) NOT NULL,
             PRIMARY KEY (update_id),
             FOREIGN KEY (update_id) REFERENCES member_updates(update_id),
             FOREIGN KEY (fingerprint) REFERENCES devices(fingerprint)
           );
         """.update.run
    _ <- sql"""
           CREATE TABLE accounts (
             zone_id CHAR(36) NOT NULL,
             account_id VARCHAR(36) NOT NULL,
             created TIMESTAMP NOT NULL,
             balance TEXT NOT NULL,
             PRIMARY KEY (zone_id, account_id),
             FOREIGN KEY (zone_id) REFERENCES zones(zone_id)
           );
         """.update.run
    _ <- sql"""
           CREATE TABLE account_updates (
             zone_id CHAR(36) NOT NULL,
             account_id VARCHAR(36) NOT NULL,
             update_id INT NOT NULL AUTO_INCREMENT,
             updated TIMESTAMP NOT NULL,
             name VARCHAR(160) NULL,
             metadata VARCHAR(1024) NULL,
             PRIMARY KEY (update_id),
             FOREIGN KEY (zone_id) REFERENCES zones(zone_id),
             FOREIGN KEY (zone_id, account_id) REFERENCES accounts(zone_id, account_id)
           );
         """.update.run
    _ <- sql"""
           CREATE TABLE account_owners (
             update_id INT NOT NULL,
             member_id VARCHAR(36) NOT NULL,
             PRIMARY KEY (update_id),
             FOREIGN KEY (update_id) REFERENCES account_updates(update_id)
           );
         """.update.run
    _ <- sql"""
           CREATE TABLE account_counts (
             time TIMESTAMP NOT NULL,
             count INT NOT NULL,
             PRIMARY KEY (count)
           );
         """.update.run
    _ <- sql"""
           CREATE TABLE transactions (
             zone_id CHAR(36) NOT NULL,
             transaction_id VARCHAR(36) NOT NULL,
             "from" VARCHAR(36) NOT NULL,
             "to" VARCHAR(36) NOT NULL,
             "value" TEXT NOT NULL,
             creator VARCHAR(36) NOT NULL,
             created TIMESTAMP NOT NULL,
             description VARCHAR(160) NULL,
             metadata VARCHAR(1024) NULL,
             PRIMARY KEY (zone_id, transaction_id),
             FOREIGN KEY (zone_id) REFERENCES zones(zone_id),
             FOREIGN KEY (zone_id, "from") REFERENCES accounts(zone_id, account_id),
             FOREIGN KEY (zone_id, "to") REFERENCES accounts(zone_id, account_id),
             FOREIGN KEY (zone_id, creator) REFERENCES members(zone_id, member_id)
            );
         """.update.run
    _ <- sql"""
           CREATE TABLE client_sessions (
             zone_id CHAR(36) NOT NULL,
             session_id INT NOT NULL AUTO_INCREMENT,
             remote_address VARCHAR(45) NULL,
             actor_ref VARCHAR(100) NOT NULL,
             public_key BLOB NULL,fingerprint CHAR(64) NULL,
             joined TIMESTAMP NOT NULL,
             quit TIMESTAMP NULL,
             PRIMARY KEY (session_id),
             FOREIGN KEY (zone_id) REFERENCES zones(zone_id),
             FOREIGN KEY (fingerprint) REFERENCES devices(fingerprint)
           );
         """.update.run
    _ <- sql"""
           CREATE TABLE tag_offsets (
             tag VARCHAR(10) NOT NULL,
             "offset" INT NOT NULL,
             PRIMARY KEY (tag)
           );
         """.update.run
  } yield ()

  private class WriterActor(override val persistenceId: String)
      extends PersistentActor {
    override def receiveRecover: Receive = Actor.emptyBehavior

    override def receiveCommand: Receive = {
      case zoneEventEnvelope: ZoneEventEnvelope =>
        persist(Tagged(zoneEventEnvelope, Set(EventTags.ZoneEventTag)))(_ => ())
    }
  }

  private def writeEvent(fixture: FixtureParam,
                         event: ZoneEvent,
                         timestamp: Instant = Instant.now()): Unit = {
    val (_, _, writer) = fixture
    writer ! ZoneEventEnvelope(
      remoteAddress = Some(remoteAddress),
      publicKey = Some(publicKey),
      timestamp = timestamp,
      zoneEvent = event
    )
  }

  private def retrieveZoneOption(zoneId: ZoneId): ConnectionIO[Option[Zone]] =
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
           WHERE zones.zone_id = $zoneId
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
            members <- retrieveAllMembers(zoneId).map(_.toMap)
            accounts <- retrieveAllAccounts(zoneId).map(_.toMap)
            transactions <- retrieveAllTransactions(zoneId).map(_.toMap)
          } yield
            Some(
              Zone(
                zoneId,
                equityAccountId,
                members,
                accounts,
                transactions,
                created,
                expires,
                name,
                metadata
              )
            )
      }
    } yield maybeZone

  private def retrieveAllMembers(
      zoneId: ZoneId): ConnectionIO[Seq[(MemberId, Member)]] = {
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
                 WHERE zone_id = $zoneId
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
             WHERE zone_id = $zoneId
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
           WHERE zone_id = $zoneId
        """
        .query[MemberId]
        .to[Vector]
      members <- memberIds
        .map(retrieve)
        .toList
        .sequence
    } yield members
  }

  private def retrieveAllAccounts(
      zoneId: ZoneId): ConnectionIO[Seq[(AccountId, Account)]] = {
    def retrieve(accountId: AccountId): ConnectionIO[(AccountId, Account)] = {
      for {
        ownerMemberIds <- sql"""
           SELECT member_id
             FROM account_owners
             WHERE update_id = (
               SELECT update_id
                 FROM account_updates
                 WHERE zone_id = $zoneId
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
             WHERE zone_id = $zoneId
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
           WHERE zone_id = $zoneId
        """
        .query[AccountId]
        .to[Vector]
      accounts <- accountIds
        .map(retrieve)
        .toList
        .sequence
    } yield accounts
  }

  private def retrieveAllTransactions(
      zoneId: ZoneId): ConnectionIO[Seq[(TransactionId, Transaction)]] =
    sql"""
       SELECT transaction_id, `from`, `to`, `value`, creator, created, description, metadata
         FROM transactions
         WHERE zone_id = $zoneId
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

  private final case class ClientSessionId(value: Long) extends AnyVal

  private final case class ClientSession(
      id: ClientSessionId,
      remoteAddress: Option[InetAddress],
      actorRef: String,
      publicKey: PublicKey,
      joined: Instant,
      quit: Option[Instant]
  )

  private def retrieveAllClientSessions(
      zoneId: ZoneId): ConnectionIO[Seq[(ClientSessionId, ClientSession)]] =
    sql"""
       SELECT client_sessions.session_id, client_sessions.remote_address, client_sessions.actor_ref, devices.public_key, client_sessions.joined, client_sessions.quit
         FROM client_sessions
         LEFT JOIN devices
         ON devices.fingerprint = client_sessions.fingerprint
         WHERE zone_id = $zoneId
      """
      .query[(ClientSessionId,
              Option[InetAddress],
              String,
              PublicKey,
              Instant,
              Option[Instant])]
      .to[Vector]
      .map(_.map {
        case values @ (id, _, _, _, _, _) =>
          id -> ClientSession.tupled(values)
      })

}
