package com.dhpcs.liquidity.analytics

import java.util.Date

import com.datastax.driver.core.Session
import com.dhpcs.liquidity.analytics.CassandraViewClient.RichListenableFuture
import com.dhpcs.liquidity.analytics.CassandraViewClient.RichMetadata
import com.dhpcs.liquidity.model._
import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}
import com.typesafe.config.Config
import okio.ByteString
import play.api.libs.json.{JsObject, Json, Reads}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future, Promise}

object CassandraViewClient {

  implicit class RichListenableFuture[A](val lf: ListenableFuture[A]) extends AnyVal {
    def asScala: Future[A] = {
      val p = Promise[A]()
      Futures.addCallback(lf, new FutureCallback[A] {
        def onFailure(t: Throwable): Unit = p.failure(t)
        def onSuccess(result: A): Unit    = p.success(result)
      })
      p.future
    }
  }

  implicit class RichMetadata(val metadata: Option[JsObject]) extends AnyVal {
    def read[A: Reads](key: String): Option[A] = metadata.flatMap(metadata => (metadata \ key).asOpt[A])
  }

  def apply(config: Config, session: Future[Session])(implicit ec: ExecutionContext): CassandraViewClient = {
    val keyspace = config.getString("liquidity.analytics.cassandra.keyspace")
    new CassandraViewClient(
      keyspace,
      for {
        session <- session
        _       <- session.executeAsync(s"""
                                           |CREATE KEYSPACE IF NOT EXISTS $keyspace
                                           |  WITH replication = {'class': 'SimpleStrategy' , 'replication_factor': '1'};
      """.stripMargin).asScala
        _       <- session.executeAsync(s"""
                                           |CREATE TABLE IF NOT EXISTS $keyspace.zone_name_changes_by_id (
                                           |  id uuid,
                                           |  changed timestamp,
                                           |  name text,
                                           |  PRIMARY KEY ((id), changed)
                                           |);
      """.stripMargin).asScala
        _       <- session.executeAsync(s"""
                                           |CREATE TABLE IF NOT EXISTS $keyspace.zones_by_id(
                                           |  id uuid,
                                           |  bucket int,
                                           |  equity_account_id int,
                                           |  created timestamp,
                                           |  modified timestamp,
                                           |  expires timestamp,
                                           |  name text,
                                           |  metadata text,
                                           |  currency text,
                                           |  PRIMARY KEY ((id), bucket)
                                           |);
      """.stripMargin).asScala
        _       <- session.executeAsync(s"""
                                           |CREATE MATERIALIZED VIEW IF NOT EXISTS $keyspace.zones_by_modified AS
                                           |  SELECT * FROM $keyspace.zones_by_id
                                           |  WHERE bucket IS NOT NULL AND modified IS NOT NULL
                                           |  PRIMARY KEY ((bucket), modified, id);
      """.stripMargin).asScala
        _       <- session.executeAsync(s"""
                                           |CREATE TABLE IF NOT EXISTS $keyspace.member_updates_by_id (
                                           |  zone_id uuid,
                                           |  id int,
                                           |  updated timestamp,
                                           |  owner_public_key blob,
                                           |  created timestamp,
                                           |  name text,
                                           |  metadata text,
                                           |  hidden boolean,
                                           |  PRIMARY KEY ((zone_id), id, updated)
                                           |);
      """.stripMargin).asScala
        _       <- session.executeAsync(s"""
                                           |CREATE TABLE IF NOT EXISTS $keyspace.members_by_zone (
                                           |  zone_id uuid,
                                           |  id int,
                                           |  owner_public_key blob,
                                           |  created timestamp,
                                           |  modified timestamp,
                                           |  name text,
                                           |  metadata text,
                                           |  hidden boolean,
                                           |  PRIMARY KEY ((zone_id), id)
                                           |);
      """.stripMargin).asScala
        _       <- session.executeAsync(s"""
                                           |CREATE TABLE IF NOT EXISTS $keyspace.account_updates_by_id (
                                           |  zone_id uuid,
                                           |  id int,
                                           |  updated timestamp,
                                           |  owner_member_ids set<int>,
                                           |  owner_names list<text>,
                                           |  created timestamp,
                                           |  modified timestamp,
                                           |  name text,
                                           |  metadata text,
                                           |  PRIMARY KEY ((zone_id), id, updated)
                                           |);
      """.stripMargin).asScala
        _       <- session.executeAsync(s"""
                                           |CREATE TABLE IF NOT EXISTS $keyspace.accounts_by_zone (
                                           |  zone_id uuid,
                                           |  id int,
                                           |  owner_member_ids set<int>,
                                           |  owner_names list<text>,
                                           |  created timestamp,
                                           |  modified timestamp,
                                           |  name text,
                                           |  metadata text,
                                           |  PRIMARY KEY ((zone_id), id)
                                           |);
      """.stripMargin).asScala
        _       <- session.executeAsync(s"""
                                           |CREATE TABLE IF NOT EXISTS $keyspace.transactions_by_zone (
                                           |  zone_id uuid,
                                           |  id int,
                                           |  "from" int,
                                           |  from_owner_names list<text>,
                                           |  "to" int,
                                           |  to_owner_names list<text>,
                                           |  value decimal,
                                           |  creator int,
                                           |  created timestamp,
                                           |  description text,
                                           |  metadata text,
                                           |  PRIMARY KEY ((zone_id), id)
                                           |);
      """.stripMargin).asScala
        _       <- session.executeAsync(s"""
                                           |CREATE TABLE IF NOT EXISTS $keyspace.balances_by_zone (
                                           |  zone_id uuid,
                                           |  account_id int,
                                           |  owner_names list<text>,
                                           |  balance decimal,
                                           |  PRIMARY KEY ((zone_id), account_id)
                                           |);
      """.stripMargin).asScala
        // TODO: Key fingerprints, (dis)connect history, review of counter use, active clients and unrecorded quits
        // (need remember entities without distributed data, plus auto purge on restart)
        _ <- session.executeAsync(s"""
                                           |CREATE TABLE IF NOT EXISTS $keyspace.clients_by_public_key (
                                           |  public_key blob,
                                           |  bucket int,
                                           |  join_count counter,
                                           |  PRIMARY KEY ((public_key), bucket)
                                           |);
      """.stripMargin).asScala
        _ <- session.executeAsync(s"""
                                           |CREATE TABLE IF NOT EXISTS $keyspace.clients_by_zone (
                                           |  zone_id uuid,
                                           |  public_key blob,
                                           |  zone_join_count int,
                                           |  last_joined timestamp,
                                           |  PRIMARY KEY ((zone_id), public_key)
                                           |);
      """.stripMargin).asScala
        _ <- session.executeAsync(s"""
                                           |CREATE MATERIALIZED VIEW IF NOT EXISTS $keyspace.zone_clients_by_public_key AS
                                           |  SELECT * FROM $keyspace.clients_by_zone
                                           |  WHERE public_key IS NOT NULL AND zone_id IS NOT NULL AND zone_join_count IS NOT NULL AND last_joined IS NOT NULL
                                           |  PRIMARY KEY ((public_key), zone_id);
      """.stripMargin).asScala
        _ <- session.executeAsync(s"""
                                           |CREATE TABLE IF NOT EXISTS $keyspace.journal_sequence_numbers_by_zone (
                                           |  id uuid,
                                           |  journal_sequence_number bigint,
                                           |  PRIMARY KEY (id)
                                           |);
      """.stripMargin).asScala
      } yield session
    )
  }
}

class CassandraViewClient private (keyspace: String, session: Future[Session])(implicit ec: ExecutionContext) {

  // TODO: Group members by table (here and above), DRY row ops

  private[this] val retrieveJournalSequenceNumberStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |SELECT journal_sequence_number
         |  FROM $keyspace.journal_sequence_numbers_by_zone
         |  WHERE id = ?
        """.stripMargin
    ).asScala)

  def retrieveJournalSequenceNumber(zoneId: ZoneId)(implicit ec: ExecutionContext): Future[Long] =
    for {
      session   <- session
      statement <- retrieveJournalSequenceNumberStatement
      resultSet <- session.executeAsync(statement.bind(zoneId.id)).asScala
    } yield
      resultSet.one match {
        case null => 0L
        case row  => row.getLong("journal_sequence_number")
      }

  private[this] val retrieveZoneStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |SELECT equity_account_id, created, expires, name, metadata
         |  FROM $keyspace.zones_by_id
         |  WHERE bucket = ? and id = ?
        """.stripMargin
    ).asScala)

  def retrieveZone(zoneId: ZoneId)(implicit ec: ExecutionContext): Future[Zone] =
    for {
      session   <- session
      statement <- retrieveZoneStatement
      resultSet <- session.executeAsync(statement.bind(1: java.lang.Integer, zoneId.id)).asScala
      row             = resultSet.one
      equityAccountId = AccountId(row.getInt("equity_account_id"))
      members      <- retrieveMembers(zoneId)
      accounts     <- retrieveAccounts(zoneId)
      transactions <- retrieveTransactions(zoneId)
      created  = row.getTimestamp("created").getTime
      expires  = row.getTimestamp("expires").getTime
      name     = Option(row.getString("name"))
      metadata = Option(row.getString("metadata")).map(Json.parse).map(_.as[JsObject])
    } yield Zone(zoneId, equityAccountId, members, accounts, transactions, created, expires, name, metadata)

  private[this] val retrieveMembersStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |SELECT id, owner_public_key, name, metadata
         |  FROM $keyspace.members_by_zone
         |  WHERE zone_id = ?
        """.stripMargin
    ).asScala)

  def retrieveMembers(zoneId: ZoneId)(implicit ec: ExecutionContext): Future[Map[MemberId, Member]] =
    for {
      session   <- session
      statement <- retrieveMembersStatement
      resultSet <- session.executeAsync(statement.bind(zoneId.id)).asScala
    } yield
      (for {
        row <- resultSet.iterator.asScala
        memberId       = MemberId(row.getInt("id"))
        ownerPublicKey = PublicKey(ByteString.of(row.getBytes("owner_public_key")))
        name           = Option(row.getString("name"))
        metadata       = Option(row.getString("metadata")).map(Json.parse).map(_.as[JsObject])
      } yield memberId -> Member(memberId, ownerPublicKey, name, metadata)).toMap

  private[this] val retrieveAccountsStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |SELECT id, owner_member_ids, name, metadata
         |  FROM $keyspace.accounts_by_zone
         |  WHERE zone_id = ?
        """.stripMargin
    ).asScala)

  def retrieveAccounts(zoneId: ZoneId)(implicit ec: ExecutionContext): Future[Map[AccountId, Account]] =
    for {
      session   <- session
      statement <- retrieveAccountsStatement
      resultSet <- session.executeAsync(statement.bind(zoneId.id)).asScala
    } yield
      (for {
        row <- resultSet.iterator.asScala
        accountId      = AccountId(row.getInt("id"))
        ownerMemberIds = row.getSet("owner_member_ids", classOf[java.lang.Integer]).asScala.map(MemberId(_)).toSet
        name           = Option(row.getString("name"))
        metadata       = Option(row.getString("metadata")).map(Json.parse).map(_.as[JsObject])
      } yield accountId -> Account(accountId, ownerMemberIds, name, metadata)).toMap

  private[this] val retrieveTransactionsStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |SELECT id, "from", "to", value, creator, created, description, metadata
         |  FROM $keyspace.transactions_by_zone
         |  WHERE zone_id = ?
        """.stripMargin
    ).asScala)

  def retrieveTransactions(zoneId: ZoneId)(implicit ec: ExecutionContext): Future[Map[TransactionId, Transaction]] =
    for {
      session   <- session
      statement <- retrieveTransactionsStatement
      resultSet <- session.executeAsync(statement.bind(zoneId.id)).asScala
    } yield
      (for {
        row <- resultSet.iterator.asScala
        transactionId = TransactionId(row.getInt("id"))
        from          = AccountId(row.getInt("from"))
        to            = AccountId(row.getInt("to"))
        value         = BigDecimal(row.getDecimal("value"))
        creator       = MemberId(row.getInt("creator"))
        created       = row.getTimestamp("created").getTime
        description   = Option(row.getString("description"))
        metadata      = Option(row.getString("metadata")).map(Json.parse).map(_.as[JsObject])
      } yield
        transactionId -> Transaction(transactionId, from, to, value, creator, created, description, metadata)).toMap

  private[this] val retrieveBalancesStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |SELECT account_id, balance
         |  FROM $keyspace.balances_by_zone
         |  WHERE zone_id = ?
        """.stripMargin
    ).asScala)

  def retrieveBalances(zoneId: ZoneId)(implicit ec: ExecutionContext): Future[Map[AccountId, BigDecimal]] =
    for {
      session   <- session
      statement <- retrieveBalancesStatement
      resultSet <- session.executeAsync(statement.bind(zoneId.id)).asScala
    } yield
      (for {
        row <- resultSet.iterator.asScala
        accountId = AccountId(row.getInt("account_id"))
        value     = BigDecimal(row.getDecimal("balance"))
      } yield accountId -> value).toMap

  private[this] val retrieveClientJoinCountsStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |SELECT public_key, zone_join_count
         |  FROM $keyspace.clients_by_zone
         |  WHERE zone_id = ?
        """.stripMargin
    ).asScala)

  def retrieveClientJoinCounts(zoneId: ZoneId)(implicit ec: ExecutionContext): Future[Map[PublicKey, Int]] =
    for {
      session   <- session
      statement <- retrieveClientJoinCountsStatement
      resultSet <- session.executeAsync(statement.bind(zoneId.id)).asScala
    } yield
      (for {
        row <- resultSet.iterator.asScala
        publicKey     = PublicKey(ByteString.of(row.getBytes("public_key")))
        zoneJoinCount = row.getInt("zone_join_count")
      } yield publicKey -> zoneJoinCount).toMap

  private[this] val addZoneNameChangeStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |INSERT INTO $keyspace.zone_name_changes_by_id (id, changed, name)
         |  VALUES (?, ?, ?)
        """.stripMargin
    ).asScala)

  def addZoneNameChange(zoneId: ZoneId, changed: Long, name: Option[String])(
      implicit ec: ExecutionContext): Future[Unit] =
    for {
      session   <- session
      statement <- addZoneNameChangeStatement
      _ <- session
        .executeAsync(
          statement.bind(
            zoneId.id,
            new Date(changed),
            name.orNull
          ))
        .asScala
    } yield ()

  private[this] val createZoneStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |INSERT INTO $keyspace.zones_by_id(id, bucket, equity_account_id, created, expires, name, metadata, currency)
         |  VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.stripMargin
    ).asScala)

  def createZone(zone: Zone)(implicit ec: ExecutionContext): Future[Unit] =
    for {
      session   <- session
      statement <- createZoneStatement
      currency = zone.metadata.read[String]("currency")
      _ <- session
        .executeAsync(
          statement.bind(
            zone.id.id,
            1: java.lang.Integer,
            zone.equityAccountId.id: java.lang.Integer,
            new Date(zone.created),
            new Date(zone.expires),
            zone.name.orNull,
            zone.metadata.map(Json.stringify).orNull,
            currency.orNull
          ))
        .asScala
    } yield ()

  def createMembers(zone: Zone)(implicit ec: ExecutionContext): Future[Unit] =
    for (_ <- Future.traverse(zone.members.values)(createMember(zone.id, zone.created))) yield ()

  def createAccounts(zone: Zone)(implicit ec: ExecutionContext): Future[Unit] =
    for (_ <- Future.traverse(zone.accounts.values)(createAccount(zone, zone.created))) yield ()

  def addTransactions(zone: Zone)(implicit ec: ExecutionContext): Future[Unit] =
    for (_ <- Future.traverse(zone.transactions.values)(addTransaction(zone))) yield ()

  def createBalances(zone: Zone, balance: BigDecimal, accounts: Iterable[Account])(
      implicit ec: ExecutionContext): Future[Unit] =
    for (_ <- Future.traverse(accounts)(createBalance(zone, balance))) yield ()

  private[this] val updateClientStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |UPDATE $keyspace.clients_by_public_key
         |  SET join_count = join_count + 1
         |  WHERE public_key = ? AND bucket = ?
        """.stripMargin
    ).asScala)

  private[this] val changeZoneNameStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |UPDATE $keyspace.zones_by_id
         |  SET modified = ?, name = ?
         |  WHERE id = ? AND bucket = ?
        """.stripMargin
    ).asScala)

  def changeZoneName(zoneId: ZoneId, modified: Long, name: Option[String])(
      implicit ec: ExecutionContext): Future[Unit] =
    for {
      session   <- session
      statement <- changeZoneNameStatement
      _ <- session
        .executeAsync(
          statement.bind(
            new Date(modified),
            name.orNull,
            zoneId.id,
            1: java.lang.Integer
          ))
        .asScala
    } yield ()

  private[this] val createMemberStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |INSERT INTO $keyspace.members_by_zone (zone_id, id, owner_public_key, created, name, metadata, hidden)
         |  VALUES (?, ?, ?, ?, ?, ?, ?)
          """.stripMargin
    ).asScala)

  def createMember(zoneId: ZoneId, created: Long)(member: Member)(implicit ec: ExecutionContext): Future[Unit] =
    for {
      session   <- session
      statement <- createMemberStatement
      hidden = member.metadata.read[Boolean]("hidden")
      _ <- session
        .executeAsync(
          statement.bind(
            zoneId.id,
            member.id.id: java.lang.Integer,
            member.ownerPublicKey.value.asByteBuffer,
            new Date(created),
            member.name.orNull,
            member.metadata.map(Json.stringify).orNull,
            hidden.map(hidden => hidden: java.lang.Boolean).orNull
          ))
        .asScala
    } yield ()

  private[this] val addMemberUpdateStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |INSERT INTO $keyspace.member_updates_by_id (zone_id, id, updated, owner_public_key, name, metadata, hidden)
         |  VALUES (?, ?, ?, ?, ?, ?, ?)
        """.stripMargin
    ).asScala)

  def addMemberUpdate(zoneId: ZoneId, updated: Long, member: Member)(implicit ec: ExecutionContext): Future[Unit] =
    for {
      session   <- session
      statement <- addMemberUpdateStatement
      hidden = member.metadata.read[Boolean]("hidden")
      _ <- session
        .executeAsync(
          statement.bind(
            zoneId.id,
            member.id.id: java.lang.Integer,
            new Date(updated),
            member.ownerPublicKey.value.asByteBuffer,
            member.name.orNull,
            member.metadata.map(Json.stringify).orNull,
            hidden.map(hidden => hidden: java.lang.Boolean).orNull
          ))
        .asScala
    } yield ()

  private[this] val updateMemberStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |UPDATE $keyspace.members_by_zone
         |  SET owner_public_key = ?, modified = ?, name = ?, metadata = ?, hidden = ?
         |  WHERE zone_id = ? AND id = ?
          """.stripMargin
    ).asScala)

  def updateMember(zoneId: ZoneId, modified: Long, member: Member)(implicit ec: ExecutionContext): Future[Unit] =
    for {
      session   <- session
      statement <- updateMemberStatement
      hidden = member.metadata.read[Boolean]("hidden")
      _ <- session
        .executeAsync(
          statement.bind(
            member.ownerPublicKey.value.asByteBuffer,
            new Date(modified),
            member.name.orNull,
            member.metadata.map(Json.stringify).orNull,
            hidden.map(hidden => hidden: java.lang.Boolean).orNull,
            zoneId.id,
            member.id.id: java.lang.Integer
          ))
        .asScala
    } yield ()

  private[this] val updateAccountsStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |UPDATE $keyspace.accounts_by_zone
         |  SET owner_names = ?
         |  WHERE zone_id = ? AND id = ?
          """.stripMargin
    ).asScala)

  def updateAccounts(zone: Zone, accounts: Iterable[Account])(implicit ec: ExecutionContext): Future[Unit] =
    for {
      session   <- session
      statement <- updateAccountsStatement
      _ <- Future.traverse(accounts)(
        account =>
          session
            .executeAsync(
              statement.bind(
                ownerNames(zone.members, account),
                zone.id.id,
                account.id.id: java.lang.Integer
              ))
            .asScala)
    } yield ()

  def updateTransactions(zone: Zone, transactions: Iterable[Transaction])(
      implicit ec: ExecutionContext): Future[Unit] =
    for (_ <- Future.traverse(transactions)(updateTransaction(zone))) yield ()

  private[this] val updateTransactionStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |UPDATE $keyspace.transactions_by_zone
         |  SET "from" = ?, from_owner_names = ?, "to" = ?, to_owner_names = ?, value = ?, creator = ?, created = ?, description = ?, metadata = ?
         |  WHERE zone_id = ? AND id = ?
          """.stripMargin
    ).asScala)

  def updateTransaction(zone: Zone)(transaction: Transaction)(implicit ec: ExecutionContext): Future[Unit] =
    for {
      session   <- session
      statement <- updateTransactionStatement
      _ <- session
        .executeAsync(
          statement.bind(
            transaction.from.id: java.lang.Integer,
            ownerNames(zone.members, zone.accounts(transaction.from)),
            transaction.to.id: java.lang.Integer,
            ownerNames(zone.members, zone.accounts(transaction.to)),
            transaction.value.underlying,
            transaction.creator.id: java.lang.Integer,
            new Date(transaction.created),
            transaction.description.orNull,
            transaction.metadata.map(Json.stringify).orNull,
            zone.id.id,
            transaction.id.id: java.lang.Integer
          ))
        .asScala
    } yield ()

  def updateBalances(zone: Zone, accounts: Iterable[Account], balances: Map[AccountId, BigDecimal])(
      implicit ec: ExecutionContext): Future[Unit] =
    for (_ <- Future.traverse(accounts.map(account => account -> balances(account.id)))(updateBalance(zone)))
      yield ()

  private[this] val updateBalanceStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |UPDATE $keyspace.balances_by_zone
         |  SET owner_names = ?, balance = ?
         |  WHERE zone_id = ? AND account_id = ?
          """.stripMargin
    ).asScala)

  def updateBalance(zone: Zone)(accountAndBalance: (Account, BigDecimal))(
      implicit ec: ExecutionContext): Future[Unit] =
    for {
      session   <- session
      statement <- updateBalanceStatement
      _ <- session
        .executeAsync({
          val (account, balance) = accountAndBalance
          statement.bind(
            ownerNames(zone.members, account),
            balance.underlying,
            zone.id.id,
            account.id.id: java.lang.Integer
          )
        })
        .asScala
    } yield ()

  private[this] val createAccountStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |INSERT INTO $keyspace.accounts_by_zone (zone_id, id, owner_member_ids, owner_names, created, name, metadata)
         |  VALUES (?, ?, ?, ?, ?, ?, ?)
          """.stripMargin
    ).asScala)

  def createAccount(zone: Zone, created: Long)(account: Account)(implicit ec: ExecutionContext): Future[Unit] =
    for {
      session   <- session
      statement <- createAccountStatement
      _ <- session
        .executeAsync(
          statement.bind(
            zone.id.id,
            account.id.id: java.lang.Integer,
            account.ownerMemberIds.map(_.id).asJava,
            ownerNames(zone.members, account),
            new Date(zone.created),
            account.name.orNull,
            account.metadata.map(Json.stringify).orNull
          ))
        .asScala
    } yield ()

  private[this] val addAccountUpdateStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |INSERT INTO $keyspace.account_updates_by_id (zone_id, id, updated, owner_member_ids, owner_names, name, metadata)
         |  VALUES (?, ?, ?, ?, ?, ?, ?)
        """.stripMargin
    ).asScala)

  def addAccountUpdate(zone: Zone, updated: Long, account: Account)(implicit ec: ExecutionContext): Future[Unit] =
    for {
      session   <- session
      statement <- addAccountUpdateStatement
      _ <- session
        .executeAsync(
          statement.bind(
            zone.id.id,
            account.id.id: java.lang.Integer,
            new Date(updated),
            account.ownerMemberIds.map(_.id).asJava,
            ownerNames(zone.members, account),
            account.name.orNull,
            account.metadata.map(Json.stringify).orNull
          ))
        .asScala
    } yield ()

  private[this] val updateAccountStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |UPDATE $keyspace.accounts_by_zone
         |  SET owner_member_ids = ?, owner_names = ?, modified = ?, name = ?, metadata = ?
         |  WHERE zone_id = ? AND id = ?
          """.stripMargin
    ).asScala)

  def updateAccount(zone: Zone, modified: Long, account: Account)(implicit ec: ExecutionContext): Future[Unit] =
    for {
      session   <- session
      statement <- updateAccountStatement
      _ <- session
        .executeAsync(
          statement.bind(
            account.ownerMemberIds.map(_.id).asJava,
            ownerNames(zone.members, account),
            new Date(modified),
            account.name.orNull,
            account.metadata.map(Json.stringify).orNull,
            zone.id.id,
            account.id.id: java.lang.Integer
          ))
        .asScala
    } yield ()

  private[this] val addTransactionStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |INSERT INTO $keyspace.transactions_by_zone (zone_id, id, "from", from_owner_names, "to", to_owner_names, value, creator, created, description, metadata)
         |  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """.stripMargin
    ).asScala)

  def addTransaction(zone: Zone)(transaction: Transaction)(implicit ec: ExecutionContext): Future[Unit] =
    for {
      session   <- session
      statement <- addTransactionStatement
      _ <- session
        .executeAsync(
          statement.bind(
            zone.id.id,
            transaction.id.id: java.lang.Integer,
            transaction.from.id: java.lang.Integer,
            ownerNames(zone.members, zone.accounts(transaction.from)),
            transaction.to.id: java.lang.Integer,
            ownerNames(zone.members, zone.accounts(transaction.to)),
            transaction.value.underlying,
            transaction.creator.id: java.lang.Integer,
            new Date(transaction.created),
            transaction.description.orNull,
            transaction.metadata.map(Json.stringify).orNull
          ))
        .asScala
    } yield ()

  private[this] val createBalanceStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |INSERT INTO $keyspace.balances_by_zone (zone_id, account_id, owner_names, balance)
         |  VALUES (?, ?, ?, ?)
          """.stripMargin
    ).asScala)

  def createBalance(zone: Zone, balance: BigDecimal)(account: Account)(implicit ec: ExecutionContext): Future[Unit] =
    for {
      session   <- session
      statement <- createBalanceStatement
      _ <- session
        .executeAsync(
          statement.bind(
            zone.id.id,
            account.id.id: java.lang.Integer,
            ownerNames(zone.members, account),
            balance.underlying
          ))
        .asScala
    } yield ()

  private[this] def ownerNames(members: Map[MemberId, Member], account: Account)(
      implicit ec: ExecutionContext): java.util.List[String] =
    account.ownerMemberIds
      .map(members)
      .toSeq
      .map(_.name.getOrElse("<unnamed>"))
      .asJava

  private[this] val updateZoneModifiedStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |UPDATE $keyspace.zones_by_id
         |  SET modified = ?
         |  WHERE id = ? AND bucket = ?
        """.stripMargin
    ).asScala)

  def updateZoneModified(zoneId: ZoneId, modified: Long)(implicit ec: ExecutionContext): Future[Unit] =
    for {
      session   <- session
      statement <- updateZoneModifiedStatement
      _ <- session
        .executeAsync(
          statement.bind(
            new Date(modified),
            zoneId.id,
            1: java.lang.Integer
          ))
        .asScala
    } yield ()

  def updateClient(publicKey: PublicKey)(implicit ec: ExecutionContext): Future[Unit] =
    for {
      session   <- session
      statement <- updateClientStatement
      _ <- session
        .executeAsync(
          statement.bind(
            publicKey.value.asByteBuffer,
            1: java.lang.Integer
          )
        )
        .asScala
    } yield ()

  private[this] val updateZoneClientStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |UPDATE $keyspace.clients_by_zone
         |  SET zone_join_count = ?, last_joined = ?
         |  WHERE zone_id = ? AND public_key = ?
        """.stripMargin
    ).asScala)

  def updateZoneClient(zoneId: ZoneId, publicKey: PublicKey, zoneJoinCount: Int, lastJoined: Long)(
      implicit ec: ExecutionContext): Future[Unit] =
    for {
      session   <- session
      statement <- updateZoneClientStatement
      _ <- session
        .executeAsync(
          statement.bind(
            zoneJoinCount: java.lang.Integer,
            new Date(lastJoined),
            zoneId.id,
            publicKey.value.asByteBuffer
          ))
        .asScala
    } yield ()

  private[this] val updateJournalSequenceNumberStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |UPDATE $keyspace.journal_sequence_numbers_by_zone
         |  SET journal_sequence_number = ?
         |  WHERE id = ?
        """.stripMargin
    ).asScala)

  def updateJournalSequenceNumber(zoneId: ZoneId, sequenceNumber: Long)(implicit ec: ExecutionContext): Future[Unit] =
    for {
      session   <- session
      statement <- updateJournalSequenceNumberStatement
      _ <- session
        .executeAsync(
          statement.bind(
            sequenceNumber: java.lang.Long,
            zoneId.id
          ))
        .asScala
    } yield ()

}
