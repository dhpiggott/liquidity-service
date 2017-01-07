package com.dhpcs.liquidity.analytics

import java.util.Date

import com.datastax.driver.core.Session
import com.dhpcs.liquidity.analytics.CassandraViewClient.RichListenableFuture
import com.dhpcs.liquidity.model._
import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}
import com.typesafe.config.Config
import okio.ByteString
import play.api.libs.json.{JsObject, Json}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future, Promise}

object CassandraViewClient {

  implicit class RichListenableFuture[T](val lf: ListenableFuture[T]) extends AnyVal {
    def asScala: Future[T] = {
      val p = Promise[T]()
      Futures.addCallback(lf, new FutureCallback[T] {
        def onFailure(t: Throwable): Unit = p.failure(t)
        def onSuccess(result: T): Unit    = p.success(result)
      })
      p.future
    }
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
        // TODO: Full currency description?
        // TODO: Review when modified is updated, perhaps only update when zone is changed and add separate value for
        // matview that updates when connection, member, account or transaction events happen too
        // TODO: Eliminate bucket?
        // TODO: Restore _by_id suffix
        _ <- session.executeAsync(s"""
                                     |CREATE TABLE IF NOT EXISTS $keyspace.zones (
                                     |  bucket int,
                                     |  id uuid,
                                     |  equity_account_id int,
                                     |  created timestamp,
                                     |  expires timestamp,
                                     |  name text,
                                     |  metadata text,
                                     |  modified timestamp,
                                     |  PRIMARY KEY ((bucket), id)
                                     |);
      """.stripMargin).asScala
        _ <- session.executeAsync(s"""
                                     |CREATE MATERIALIZED VIEW IF NOT EXISTS $keyspace.zones_by_modified AS
                                     |  SELECT * FROM $keyspace.zones
                                     |  WHERE modified IS NOT NULL AND id IS NOT NULL
                                     |  PRIMARY KEY ((bucket), modified, id);
      """.stripMargin).asScala
        _ <- session.executeAsync(s"""
                                     |CREATE TABLE IF NOT EXISTS $keyspace.journal_sequence_numbers_by_zone (
                                     |  id uuid,
                                     |  journal_sequence_number bigint,
                                     |  PRIMARY KEY (id)
                                     |);
      """.stripMargin).asScala
        // TODO: Key fingerprints, active clients and unrecorded quits
        _ <- session.executeAsync(s"""
                                     |CREATE TABLE IF NOT EXISTS $keyspace.clients_by_zone (
                                     |  zone_id uuid,
                                     |  public_key blob,
                                     |  total_joins int,
                                     |  last_joined timestamp,
                                     |  PRIMARY KEY ((zone_id), public_key)
                                     |);
      """.stripMargin).asScala
        // TODO
//        _ <- session.executeAsync(s"""
//                                     |CREATE MATERIALIZED VIEW IF NOT EXISTS $keyspace.clients_by_public_key AS
//                                     |  SELECT * FROM $keyspace.clients_by_zone
//                                     |  WHERE modified IS NOT NULL AND id IS NOT NULL
//                                     |  PRIMARY KEY ((public_key), zone_id, is_joined_now, last_joined);
//      """.stripMargin).asScala
        // TODO: Add modified timestamp
        _ <- session.executeAsync(s"""
                                     |CREATE TABLE IF NOT EXISTS $keyspace.members_by_zone (
                                     |  zone_id uuid,
                                     |  id int,
                                     |  owner_public_key blob,
                                     |  name text,
                                     |  metadata text,
                                     |  PRIMARY KEY ((zone_id), id)
                                     |);
      """.stripMargin).asScala
        // TODO: Add modified timestamp
        _ <- session.executeAsync(s"""
                                     |CREATE TABLE IF NOT EXISTS $keyspace.accounts_by_zone (
                                     |  zone_id uuid,
                                     |  id int,
                                     |  owner_member_ids set<int>,
                                     |  owner_names list<text>,
                                     |  name text,
                                     |  metadata text,
                                     |  PRIMARY KEY ((zone_id), id)
                                     |);
      """.stripMargin).asScala
        _ <- session.executeAsync(s"""
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
        _ <- session.executeAsync(s"""
                                     |CREATE TABLE IF NOT EXISTS $keyspace.balances_by_zone (
                                     |  zone_id uuid,
                                     |  account_id int,
                                     |  owner_names list<text>,
                                     |  balance decimal,
                                     |  PRIMARY KEY ((zone_id), account_id)
                                     |);
      """.stripMargin).asScala
      } yield session
    )
  }
}

class CassandraViewClient private (keyspace: String, session: Future[Session])(implicit ec: ExecutionContext) {

  // TODO: DRY row ops

  private[this] val retrieveJournalSequenceNumberStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |SELECT journal_sequence_number
         |FROM $keyspace.journal_sequence_numbers_by_zone
         |WHERE id = ?
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

  private[this] val retrieveConnectionCountsStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |SELECT public_key, total_joins
         |FROM $keyspace.clients_by_zone
         |WHERE zone_id = ?
        """.stripMargin
    ).asScala)

  def retrieveConnectionCounts(zoneId: ZoneId)(implicit ec: ExecutionContext): Future[Map[PublicKey, Int]] =
    for {
      session   <- session
      statement <- retrieveConnectionCountsStatement
      resultSet <- session.executeAsync(statement.bind(zoneId.id)).asScala
    } yield
      (for {
        row <- resultSet.iterator.asScala
        publicKey  = PublicKey(ByteString.of(row.getBytes("public_key")))
        totalJoins = row.getInt("total_joins")
      } yield publicKey -> totalJoins).toMap

  private[this] val retrieveMembersStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |SELECT id, owner_public_key, name, metadata
         |FROM $keyspace.members_by_zone
         |WHERE zone_id = ?
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
         |FROM $keyspace.accounts_by_zone
         |WHERE zone_id = ?
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
         |FROM $keyspace.transactions_by_zone
         |WHERE zone_id = ?
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
         |FROM $keyspace.balances_by_zone
         |WHERE zone_id = ?
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

  private[this] val retrieveZoneStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |SELECT equity_account_id, created, expires, name, metadata
         |FROM $keyspace.zones
         |WHERE bucket = ? and id = ?
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

  private[this] def ownerNames(members: Map[MemberId, Member], account: Account)(
      implicit ec: ExecutionContext): java.util.List[String] =
    account.ownerMemberIds
      .map(members)
      .toSeq
      .map(_.name.getOrElse("<unnamed>"))
      .asJava

  private[this] val createZoneStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |INSERT INTO $keyspace.zones (bucket, id, equity_account_id, created, expires, name, metadata, modified)
         |VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.stripMargin
    ).asScala)

  def createZone(zone: Zone)(implicit ec: ExecutionContext): Future[Unit] =
    for {
      session   <- session
      statement <- createZoneStatement
      _ <- session
        .executeAsync(
          statement.bind(
            1: java.lang.Integer,
            zone.id.id,
            zone.equityAccountId.id: java.lang.Integer,
            new Date(zone.created),
            new Date(zone.expires),
            zone.name.orNull,
            zone.metadata.map(Json.stringify).orNull,
            new Date(zone.created)
          ))
        .asScala
    } yield ()

  private[this] val updateClientStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |UPDATE $keyspace.clients_by_zone
         |SET total_joins = ?, last_joined = ?
         |WHERE zone_id = ? AND public_key = ?
        """.stripMargin
    ).asScala)

  def updateClient(zoneId: ZoneId, publicKey: PublicKey, totalJoins: Int, lastJoined: Long)(
      implicit ec: ExecutionContext): Future[Unit] =
    for {
      session   <- session
      statement <- updateClientStatement
      _ <- session
        .executeAsync(
          statement.bind(
            totalJoins: java.lang.Integer,
            new Date(lastJoined),
            zoneId.id,
            publicKey.value.asByteBuffer
          ))
        .asScala
    } yield ()

  private[this] val changeZoneNameStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |UPDATE $keyspace.zones
         |SET name = ?
         |WHERE bucket = ? AND id = ?
        """.stripMargin
    ).asScala)

  def changeZoneName(zoneId: ZoneId, name: Option[String])(implicit ec: ExecutionContext): Future[Unit] =
    for {
      session   <- session
      statement <- changeZoneNameStatement
      _ <- session
        .executeAsync(
          statement.bind(
            name.orNull,
            1: java.lang.Integer,
            zoneId.id
          ))
        .asScala
    } yield ()

  private[this] val createMemberStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |INSERT INTO $keyspace.members_by_zone (zone_id, id, owner_public_key, name, metadata)
         |VALUES (?, ?, ?, ?, ?)
          """.stripMargin
    ).asScala)

  def createMembers(zone: Zone)(implicit ec: ExecutionContext): Future[Unit] =
    for (_ <- Future.traverse(zone.members.values)(createMember(zone.id))) yield ()

  def createMember(zoneId: ZoneId)(member: Member)(implicit ec: ExecutionContext): Future[Unit] =
    for {
      session   <- session
      statement <- createMemberStatement
      _ <- session
        .executeAsync(
          statement.bind(
            zoneId.id,
            member.id.id: java.lang.Integer,
            member.ownerPublicKey.value.asByteBuffer,
            member.name.orNull,
            member.metadata.map(Json.stringify).orNull
          ))
        .asScala
    } yield ()

  private[this] val updateMemberStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |UPDATE $keyspace.members_by_zone
         |SET owner_public_key = ?, name = ?, metadata = ?
         |WHERE zone_id = ? AND id = ?
          """.stripMargin
    ).asScala)

  def updateMember(zoneId: ZoneId, member: Member)(implicit ec: ExecutionContext): Future[Unit] =
    for {
      session   <- session
      statement <- updateMemberStatement
      _ <- session
        .executeAsync(
          statement.bind(
            member.ownerPublicKey.value.asByteBuffer,
            member.name.orNull,
            member.metadata.map(Json.stringify).orNull,
            zoneId.id,
            member.id.id: java.lang.Integer
          ))
        .asScala
    } yield ()

  private[this] val createAccountStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |INSERT INTO $keyspace.accounts_by_zone (zone_id, id, owner_member_ids, owner_names, name, metadata)
         |VALUES (?, ?, ?, ?, ?, ?)
          """.stripMargin
    ).asScala)

  def createAccounts(zone: Zone)(implicit ec: ExecutionContext): Future[Unit] =
    for (_ <- Future.traverse(zone.accounts.values)(createAccount(zone))) yield ()

  def createAccount(zone: Zone)(account: Account)(implicit ec: ExecutionContext): Future[Unit] =
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
            account.name.orNull,
            account.metadata.map(Json.stringify).orNull
          ))
        .asScala
    } yield ()

  private[this] val updateAccountStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |UPDATE $keyspace.accounts_by_zone
         |SET owner_member_ids = ?, owner_names = ?, name = ?, metadata = ?
         |WHERE zone_id = ? AND id = ?
          """.stripMargin
    ).asScala)

  def updateAccounts(zone: Zone, accounts: Iterable[Account])(implicit ec: ExecutionContext): Future[Unit] =
    for (_ <- Future.traverse(accounts)(updateAccount(zone))) yield ()

  def updateAccount(zone: Zone)(account: Account)(implicit ec: ExecutionContext): Future[Unit] =
    for {
      session   <- session
      statement <- updateAccountStatement
      _ <- session
        .executeAsync(
          statement.bind(
            account.ownerMemberIds.map(_.id).asJava,
            ownerNames(zone.members, account),
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
         |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """.stripMargin
    ).asScala)

  def addTransactions(zone: Zone)(implicit ec: ExecutionContext): Future[Unit] =
    for (_ <- Future.traverse(zone.transactions.values)(addTransaction(zone))) yield ()

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

  private[this] val updateTransactionStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |UPDATE $keyspace.transactions_by_zone
         |SET "from" = ?, from_owner_names = ?, "to" = ?, to_owner_names = ?, value = ?, creator = ?, created = ?, description = ?, metadata = ?
         |WHERE zone_id = ? AND id = ?
          """.stripMargin
    ).asScala)

  def updateTransactions(zone: Zone, transactions: Iterable[Transaction])(
      implicit ec: ExecutionContext): Future[Unit] =
    for (_ <- Future.traverse(transactions)(updateTransaction(zone))) yield ()

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

  private[this] val createBalanceStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |INSERT INTO $keyspace.balances_by_zone (zone_id, account_id, owner_names, balance)
         |VALUES (?, ?, ?, ?)
          """.stripMargin
    ).asScala)

  def createBalances(zone: Zone, balance: BigDecimal, accounts: Iterable[Account])(
      implicit ec: ExecutionContext): Future[Unit] =
    for (_ <- Future.traverse(accounts)(createBalance(zone, balance))) yield ()

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

  private[this] val updateBalanceStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |UPDATE $keyspace.balances_by_zone
         |SET owner_names = ?, balance = ?
         |WHERE zone_id = ? AND account_id = ?
          """.stripMargin
    ).asScala)

  def updateBalances(zone: Zone, accounts: Iterable[Account], balances: Map[AccountId, BigDecimal])(
      implicit ec: ExecutionContext): Future[Unit] =
    for (_ <- Future.traverse(accounts.map(account => account -> balances(account.id)))(updateBalance(zone)))
      yield ()

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

  private[this] val updateZoneModifiedStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |UPDATE $keyspace.zones
         |SET modified = ?
         |WHERE bucket = ? AND id = ?
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
            1: java.lang.Integer,
            zoneId.id
          ))
        .asScala
    } yield ()

  private[this] val updateJournalSequenceNumberStatement = session.flatMap(
    _.prepareAsync(
      s"""
         |UPDATE $keyspace.journal_sequence_numbers_by_zone
         |SET journal_sequence_number = ?
         |WHERE id = ?
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
