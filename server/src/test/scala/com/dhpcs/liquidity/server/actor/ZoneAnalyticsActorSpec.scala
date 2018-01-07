package com.dhpcs.liquidity.server.actor

import java.net.InetAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.ask
import akka.persistence.PersistentActor
import akka.persistence.inmemory.extension.{
  InMemoryJournalStorage,
  StorageExtension
}
import akka.persistence.inmemory.query.scaladsl.InMemoryReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit.TestProbe
import akka.typed.cluster.ActorRefResolver
import akka.typed.scaladsl.adapter._
import akka.util.Timeout
import cats.effect.IO
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.persistence.zone._
import com.dhpcs.liquidity.server.LiquidityServer.TransactIoToFuture
import com.dhpcs.liquidity.server.SqlAnalyticsStore.ClientSessionsStore.ClientSession
import com.dhpcs.liquidity.server.actor.ZoneAnalyticsActorSpec._
import com.dhpcs.liquidity.server.{
  InmemoryPersistenceTestFixtures,
  SqlAnalyticsStore
}
import doobie._
import doobie.implicits._
import org.h2.tools.Server
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import org.scalatest.{BeforeAndAfterAll, Outcome, fixture}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

object ZoneAnalyticsActorSpec {

  private val initAnalytics: ConnectionIO[Unit] = for {
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
             public_key BLOB NOT NULL,
             fingerprint CHAR(64) NOT NULL,
             PRIMARY KEY (update_id),
             FOREIGN KEY (update_id) REFERENCES member_updates(update_id)
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
             public_key BLOB NULL,
             fingerprint CHAR(64) NULL,
             joined TIMESTAMP NOT NULL,
             quit TIMESTAMP NULL,
             PRIMARY KEY (session_id),
             FOREIGN KEY (zone_id) REFERENCES zones(zone_id)
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
        persist(zoneEventEnvelope)(_ => ())
    }
  }
}

class ZoneAnalyticsActorSpec
    extends fixture.FreeSpec
    with InmemoryPersistenceTestFixtures
    with BeforeAndAfterAll
    with Eventually
    with ScalaFutures
    with IntegrationPatience {

  private[this] val readJournal = PersistenceQuery(system)
    .readJournalFor[InMemoryReadJournal](InMemoryReadJournal.Identifier)

  private[this] implicit val mat: Materializer = ActorMaterializer()

  private[this] val remoteAddress = InetAddress.getLoopbackAddress
  private[this] val publicKey = PublicKey(rsaPublicKey.getEncoded)

  private[this] val h2Server = Server.createTcpServer()
  private[this] val transactIoToFuture = new TransactIoToFuture(
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool()))

  override def beforeAll(): Unit = {
    super.beforeAll()
    h2Server.start()
    ()
  }

  override def afterAll(): Unit = {
    h2Server.stop()
    super.afterAll()
  }

  override protected type FixtureParam = (Transactor[IO], ZoneId, ActorRef)

  override protected def withFixture(test: OneArgTest): Outcome = {
    val transactor = Transactor.fromDriverManager[IO](
      driver = "org.h2.Driver",
      url =
        s"jdbc:h2:mem:liquidity_analytics_${UUID.randomUUID()};" +
          "DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
      user = "sa",
      pass = ""
    )
    initAnalytics.transact(transactor).unsafeRunSync()
    val analytics = system.spawn(
      ZoneAnalyticsActor.singletonBehavior(
        readJournal,
        transactor,
        transactIoToFuture
      ),
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

  "The ZoneAnalyticsActor" - {
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

  private[this] def zoneCreated(fixture: FixtureParam): Zone = {
    val (transactor, zoneId, _) = fixture
    val created = System.currentTimeMillis
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
      expires = created + 7.days.toMillis,
      name = Some("Dave's Game"),
      metadata = None
    )
    writeEvent(fixture, ZoneCreatedEvent(zone))
    eventually {
      val zoneCount = transactIoToFuture(transactor)(
        SqlAnalyticsStore.ZoneStore.retrieveCount
      ).futureValue
      assert(zoneCount === 1)
      val maybeZone = transactIoToFuture(transactor)(
        SqlAnalyticsStore.ZoneStore.retrieveOption(zoneId)
      ).futureValue
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
      val (_, clientSession) = transactIoToFuture(transactor)(
        SqlAnalyticsStore.ClientSessionsStore.retrieveAll(zoneId)
      ).futureValue.head
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
      val (_, clientSession) = transactIoToFuture(transactor)(
        SqlAnalyticsStore.ClientSessionsStore.retrieveAll(zoneId)
      ).futureValue.head
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
      val maybeZone = transactIoToFuture(transactor)(
        SqlAnalyticsStore.ZoneStore.retrieveOption(zoneId)
      ).futureValue
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
      val membersCount = transactIoToFuture(transactor)(
        SqlAnalyticsStore.MembersStore.retrieveCount
      ).futureValue
      assert(membersCount === 2)
      val memberOwnersCount = transactIoToFuture(transactor)(
        SqlAnalyticsStore.MemberUpdatesStore.MemberOwnersStore.retrieveCount
      ).futureValue
      assert(memberOwnersCount === 1)
      val members = transactIoToFuture(transactor)(
        SqlAnalyticsStore.MembersStore.retrieveAll(zoneId)
      ).futureValue
      assert(members(member.id) === member)
    }
    member
  }

  private[this] def memberUpdated(fixture: FixtureParam,
                                  member: Member): Unit = {
    val (transactor, zoneId, _) = fixture
    writeEvent(fixture, MemberUpdatedEvent(member.copy(name = None)))
    eventually {
      val members = transactIoToFuture(transactor)(
        SqlAnalyticsStore.MembersStore.retrieveAll(zoneId)
      ).futureValue
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
      val accountsCount = transactIoToFuture(transactor)(
        SqlAnalyticsStore.AccountsStore.retrieveCount
      ).futureValue
      assert(accountsCount === 2)
      val accounts = transactIoToFuture(transactor)(
        SqlAnalyticsStore.AccountsStore.retrieveAll(zoneId)
      ).futureValue
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
      val accounts = transactIoToFuture(transactor)(
        SqlAnalyticsStore.AccountsStore.retrieveAll(zoneId)
      ).futureValue
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
      created = System.currentTimeMillis(),
      description = Some("Jenny's Lottery Win"),
      metadata = None
    )
    writeEvent(fixture, TransactionAddedEvent(transaction))
    eventually {
      val transactionCount = transactIoToFuture(transactor)(
        SqlAnalyticsStore.TransactionsStore.retrieveCount
      ).futureValue
      assert(transactionCount === 1)
      val transactions = transactIoToFuture(transactor)(
        SqlAnalyticsStore.TransactionsStore.retrieveAll(zoneId)
      ).futureValue
      assert(transactions(transaction.id) === transaction)
      val sourceBalance = transactIoToFuture(transactor)(
        SqlAnalyticsStore.AccountsStore.retrieveBalance(zoneId,
                                                        zone.equityAccountId)
      ).futureValue
      assert(sourceBalance === BigDecimal(-5000))
      val destinationBalance = transactIoToFuture(transactor)(
        SqlAnalyticsStore.AccountsStore.retrieveBalance(zoneId, to)
      ).futureValue
      assert(destinationBalance === BigDecimal(5000))
    }
    ()
  }

  private[this] def writeEvent(fixture: FixtureParam,
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
}
