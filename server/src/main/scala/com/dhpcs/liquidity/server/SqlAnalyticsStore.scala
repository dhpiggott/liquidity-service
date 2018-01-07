package com.dhpcs.liquidity.server

import java.net.InetAddress
import java.time.Instant

import akka.persistence.query.Sequence
import cats.instances.list._
import cats.syntax.applicative._
import cats.syntax.apply._
import cats.syntax.traverse._
import com.dhpcs.liquidity.model._
import com.trueaccord.scalapb.json.JsonFormat
import doobie._
import doobie.implicits._
import okio.ByteString

object SqlAnalyticsStore {

  implicit val PublicKeyMeta: Meta[PublicKey] =
    Meta[Array[Byte]]
      .xmap(bytes => PublicKey(ByteString.of(bytes: _*)), _.value.toByteArray)

  implicit val InetAddressMeta: Meta[InetAddress] =
    Meta[String].xmap(InetAddress.getByName, _.getHostAddress)

  implicit val ZoneIdMeta: Meta[ZoneId] = Meta[String].xmap(ZoneId(_), _.value)

  implicit val MemberIdMeta: Meta[MemberId] =
    Meta[String].xmap(MemberId, _.value)

  implicit val AccountIdMeta: Meta[AccountId] =
    Meta[String].xmap(AccountId, _.value)

  implicit val TransactionIdMeta: Meta[TransactionId] =
    Meta[String].xmap(TransactionId, _.value)

  implicit val StructMeta: Meta[com.google.protobuf.struct.Struct] =
    Meta[String].xmap(
      JsonFormat.fromJsonString[com.google.protobuf.struct.Struct],
      JsonFormat.toJsonString[com.google.protobuf.struct.Struct])

  object ZoneStore {

    def insert(zone: Zone): ConnectionIO[Unit] =
      for {
        _ <- sql"""
               INSERT INTO zones (zone_id, equity_account_id, created, expires, metadata)
                 VALUES (${zone.id}, ${zone.equityAccountId}, ${Instant.ofEpochMilli(
          zone.created)}, ${Instant.ofEpochMilli(zone.expires)}, ${zone.metadata})
             """.update.run
        _ <- (ZoneNameChangeStore.insert(zone.id,
                                         zone.name,
                                         Instant.ofEpochMilli(zone.created)),
              for {
                _ <- zone.members.values.toList
                  .map(MembersStore
                    .insert(zone.id, _, Instant.ofEpochMilli(zone.created)))
                  .sequence
                _ <- zone.accounts.values.toList
                  .map(
                    AccountsStore.insert(zone.id,
                                         _,
                                         Instant.ofEpochMilli(zone.created),
                                         BigDecimal(0)))
                  .sequence
                _ <- zone.transactions.values.toList
                  .map(TransactionsStore.insert(zone.id, _))
                  .sequence
              } yield ()).tupled
      } yield ()

    def update(zoneId: ZoneId, modified: Instant): ConnectionIO[Unit] =
      for (_ <- sql"""
             UPDATE zones
               SET modified = $modified
               WHERE zone_id = $zoneId
           """.update.run)
        yield ()

    def retrieveCount: ConnectionIO[Long] =
      sql"""
           SELECT COUNT(*) FROM zones
         """
        .query[Long]
        .unique

    def retrieveOption(zoneId: ZoneId): ConnectionIO[Option[Zone]] =
      for {
        maybeZoneMetadata <- sql"""
               SELECT equity_account_id, created, expires, metadata
                 FROM zones
                 WHERE zone_id = $zoneId
             """
          .query[(AccountId,
                  Instant,
                  Instant,
                  Option[com.google.protobuf.struct.Struct])]
          .option
        maybeZone <- maybeZoneMetadata match {
          case None =>
            None.pure[ConnectionIO]

          case Some((equityAccountId, created, expires, metadata)) =>
            for {
              (membersAccountsTransactionsAndName) <- (MembersStore.retrieveAll(
                                                         zoneId),
                                                       AccountsStore
                                                         .retrieveAll(zoneId),
                                                       TransactionsStore
                                                         .retrieveAll(zoneId),
                                                       ZoneNameChangeStore
                                                         .retrieveLatest(
                                                           zoneId)).tupled
              (members, accounts, transactions, name) = membersAccountsTransactionsAndName
            } yield
              Some(
                Zone(zoneId,
                     equityAccountId,
                     members,
                     accounts,
                     transactions,
                     created.toEpochMilli(),
                     expires.toEpochMilli(),
                     name,
                     metadata))
        }
      } yield maybeZone

  }

  object ZoneNameChangeStore {

    def insert(zoneId: ZoneId,
               name: Option[String],
               changed: Instant): ConnectionIO[Unit] =
      for (_ <- sql"""
             INSERT INTO zone_name_changes (zone_id, name, changed)
               VALUES ($zoneId, $name, $changed)
           """.update.run)
        yield ()

    def retrieveLatest(zoneId: ZoneId): ConnectionIO[Option[String]] =
      sql"""
           SELECT name FROM zone_name_changes
             WHERE zone_id = $zoneId
             ORDER BY change_id DESC
             LIMIT 1
         """
        .query[Option[String]]
        .unique

  }

  object MembersStore {

    def insert(zoneId: ZoneId,
               member: Member,
               created: Instant): ConnectionIO[Unit] =
      for {
        _ <- sql"""
               INSERT INTO members (zone_id, member_id, created)
                 VALUES ($zoneId, ${member.id}, $created)
             """.update.run
        _ <- MemberUpdatesStore.insert(zoneId, member, created)
      } yield ()

    def retrieveCount: ConnectionIO[Long] =
      sql"""
           SELECT COUNT(*) FROM members
         """
        .query[Long]
        .unique

    def retrieveAll(zoneId: ZoneId): ConnectionIO[Map[MemberId, Member]] =
      for {
        memberIds <- sql"""
               SELECT member_id
                 FROM members
                 WHERE zone_id = $zoneId
             """
          .query[MemberId]
          .vector
        memberOwnerPublicKeysNamesAndMetadata <- memberIds
          .map(
            memberId =>
              MemberUpdatesStore
                .retrieveLatest(zoneId, memberId)
                .map(memberId -> _))
          .toList
          .sequence
          .map(_.toMap)
      } yield
        memberIds.map { memberId =>
          val (ownerPublicKeys, name, metadata) =
            memberOwnerPublicKeysNamesAndMetadata(memberId)
          memberId -> Member(memberId, ownerPublicKeys, name, metadata)
        }.toMap

  }

  object MemberUpdatesStore {

    def insert(zoneId: ZoneId,
               member: Member,
               updated: Instant): ConnectionIO[Unit] =
      for {
        updateId <- sql"""
               INSERT INTO member_updates (zone_id, member_id, updated, name, metadata)
                 VALUES ($zoneId, ${member.id}, $updated, ${member.name}, ${member.metadata})
             """.update
          .withUniqueGeneratedKeys[Long]("update_id")
        _ <- member.ownerPublicKeys
          .map(MemberOwnersStore.insert(updateId, _))
          .toList
          .sequence
      } yield ()

    def retrieveLatest(zoneId: ZoneId, memberId: MemberId)
      : ConnectionIO[(Set[PublicKey],
                      Option[String],
                      Option[com.google.protobuf.struct.Struct])] =
      for {
        updateIdNameAndMetadata <- sql"""
               SELECT update_id, name, metadata FROM member_updates
                 WHERE zone_id = $zoneId AND member_id = $memberId
                 ORDER BY update_id DESC
                 LIMIT 1
             """
          .query[(Long,
                  Option[String],
                  Option[com.google.protobuf.struct.Struct])]
          .unique
        (updateId, name, metadata) = updateIdNameAndMetadata
        ownerPublicKeys <- MemberOwnersStore.retrieveAll(updateId)
      } yield (ownerPublicKeys, name, metadata)

    object MemberOwnersStore {

      def insert(updateId: Long, publicKey: PublicKey): ConnectionIO[Unit] =
        for (_ <- sql"""
               INSERT INTO member_owners (update_id, public_key, fingerprint)
                 VALUES ($updateId, $publicKey, ${publicKey.fingerprint})
             """.update.run)
          yield ()

      def retrieveCount: ConnectionIO[Long] =
        sql"""
             SELECT COUNT(DISTINCT public_key)
               FROM member_owners
           """
          .query[Long]
          .unique

      def retrieveAll(updateId: Long): ConnectionIO[Set[PublicKey]] =
        sql"""
             SELECT public_key
               FROM member_owners
               WHERE update_id = $updateId
           """
          .query[PublicKey]
          .to[Set]

    }
  }

  object AccountsStore {

    def insert(zoneId: ZoneId,
               account: Account,
               created: Instant,
               balance: BigDecimal): ConnectionIO[Unit] =
      for {
        _ <- sql"""
               INSERT INTO accounts (zone_id, account_id, created, balance)
                 VALUES ($zoneId, ${account.id}, $created, $balance)
             """.update.run
        _ <- AccountUpdatesStore.insert(zoneId, account, created)
      } yield ()

    def update(zoneId: ZoneId,
               accountId: AccountId,
               balance: BigDecimal): ConnectionIO[Unit] =
      for (_ <- sql"""
             UPDATE accounts
               SET balance = $balance
               WHERE zone_id = $zoneId AND account_id = $accountId
           """.update.run)
        yield ()

    def retrieveCount: ConnectionIO[Long] =
      sql"""
           SELECT COUNT(*) FROM accounts
         """
        .query[Long]
        .unique

    def retrieveBalance(zoneId: ZoneId,
                        accountId: AccountId): ConnectionIO[BigDecimal] =
      sql"""
           SELECT balance FROM accounts
             WHERE zone_id = $zoneId AND account_id = $accountId
         """
        .query[BigDecimal]
        .unique

    def retrieveAll(zoneId: ZoneId): ConnectionIO[Map[AccountId, Account]] =
      for {
        accountIds <- sql"""
               SELECT account_id
                 FROM accounts
                 WHERE zone_id = $zoneId
             """
          .query[AccountId]
          .vector
        accountOwnerMemberIdsNamesAndMetadata <- accountIds
          .map(
            accountId =>
              AccountUpdatesStore
                .retrieveLatest(zoneId, accountId)
                .map(accountId -> _))
          .toList
          .sequence
          .map(_.toMap)
      } yield
        accountIds.map { accountId =>
          val (ownerMemberIds, name, metadata) =
            accountOwnerMemberIdsNamesAndMetadata(accountId)
          accountId -> Account(accountId, ownerMemberIds, name, metadata)
        }.toMap

    def retrieveAllBalances(
        zoneId: ZoneId): ConnectionIO[Map[AccountId, BigDecimal]] =
      sql"""
           SELECT account_id, balance
             FROM accounts
             WHERE zone_id = $zoneId
         """
        .query[(AccountId, BigDecimal)]
        .vector
        .map(_.toMap)

  }

  object AccountUpdatesStore {

    def insert(zoneId: ZoneId,
               account: Account,
               updated: Instant): ConnectionIO[Unit] =
      for {
        updateId <- sql"""
               INSERT INTO account_updates (zone_id, account_id, updated, name, metadata)
                 VALUES ($zoneId, ${account.id}, $updated, ${account.name}, ${account.metadata})
             """.update
          .withUniqueGeneratedKeys[Long]("update_id")
        _ <- account.ownerMemberIds
          .map(AccountOwnersStore.insert(updateId, _))
          .toList
          .sequence
      } yield ()

    def retrieveLatest(zoneId: ZoneId, accountId: AccountId)
      : ConnectionIO[(Set[MemberId],
                      Option[String],
                      Option[com.google.protobuf.struct.Struct])] =
      for {
        updateIdNameAndMetadata <- sql"""
               SELECT update_id, name, metadata
                 FROM account_updates
                 WHERE zone_id = $zoneId AND account_id = $accountId
                 ORDER BY update_id DESC
                 LIMIT 1
             """
          .query[(Long,
                  Option[String],
                  Option[com.google.protobuf.struct.Struct])]
          .unique
        (updateId, name, metadata) = updateIdNameAndMetadata
        ownerMemberIds <- AccountOwnersStore.retrieveAll(updateId)
      } yield (ownerMemberIds, name, metadata)

    object AccountOwnersStore {

      def insert(updateId: Long, memberId: MemberId): ConnectionIO[Unit] =
        for (_ <- sql"""
               INSERT INTO account_owners (update_id, member_id)
                 VALUES ($updateId, $memberId)
             """.update.run)
          yield ()

      def retrieveAll(updateId: Long): ConnectionIO[Set[MemberId]] =
        sql"""
             SELECT member_id
               FROM account_owners
               WHERE update_id = $updateId
           """
          .query[MemberId]
          .to[Set]

    }
  }

  object TransactionsStore {

    def insert(zoneId: ZoneId, transaction: Transaction): ConnectionIO[Unit] =
      for (_ <- sql"""
             INSERT INTO transactions (zone_id, transaction_id, `from`, `to`, `value`, creator, created, description, metadata)
               VALUES ($zoneId, ${transaction.id}, ${transaction.from}, ${transaction.to}, ${transaction.value}, ${transaction.creator}, ${Instant
             .ofEpochMilli(transaction.created)}, ${transaction.description}, ${transaction.metadata})
           """.update.run)
        yield ()

    def retrieveCount: ConnectionIO[Long] =
      sql"""
           SELECT COUNT(*)
             FROM transactions
         """
        .query[Long]
        .unique

    def retrieveAll(
        zoneId: ZoneId): ConnectionIO[Map[TransactionId, Transaction]] =
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
        .vector
        .map(_.map {
          case (id, from, to, value, creator, created, description, metadata) =>
            id -> Transaction(id,
                              from,
                              to,
                              value,
                              creator,
                              created.toEpochMilli(),
                              description,
                              metadata)
        }.toMap)

  }

  object ClientSessionsStore {

    final case class ClientSessionId(value: Long) extends AnyVal

    final case class ClientSession(
        id: ClientSessionId,
        remoteAddress: Option[InetAddress],
        actorRef: String,
        publicKey: PublicKey,
        joined: Instant,
        quit: Option[Instant]
    )

    def insert(zoneId: ZoneId,
               remoteAddress: Option[InetAddress],
               actorRef: String,
               publicKey: Option[PublicKey],
               joined: Instant): ConnectionIO[Unit] =
      for (_ <- sql"""
             INSERT INTO client_sessions (zone_id, remote_address, actor_ref, public_key, fingerprint, joined)
               VALUES ($zoneId, $remoteAddress, $actorRef, $publicKey, ${publicKey
             .map(_.fingerprint)}, $joined)
           """.update.run) yield ()

    def update(sessionId: Long, quit: Instant): ConnectionIO[Unit] =
      for (_ <- sql"""
             UPDATE client_sessions
               SET quit = $quit
               WHERE session_id = $sessionId
           """.update.run)
        yield ()

    def retrieve(zoneId: ZoneId, actorRef: String): ConnectionIO[Long] =
      sql"""
           SELECT session_id FROM client_sessions
             WHERE zone_id = $zoneId AND actor_ref = $actorRef AND quit IS NULL
             ORDER BY session_id
             LIMIT 1
         """
        .query[Long]
        .unique

    def retrieveAll(
        zoneId: ZoneId): ConnectionIO[Map[ClientSessionId, ClientSession]] =
      sql"""
           SELECT session_id, remote_address, actor_ref, public_key, joined, quit
             FROM client_sessions
             WHERE zone_id = $zoneId
         """
        .query[(ClientSessionId,
                Option[InetAddress],
                String,
                PublicKey,
                Instant,
                Option[Instant])]
        .vector
        .map(_.map {
          case (id, remoteAddress, actorRef, publicKey, joined, quit) =>
            id -> ClientSession(id,
                                remoteAddress,
                                actorRef,
                                publicKey,
                                joined,
                                quit)
        }.toMap)

  }

  object TagOffsetsStore {

    def insert(tag: String, offset: Sequence): ConnectionIO[Unit] =
      for (_ <- sql"""
             INSERT INTO tag_offsets (tag, `offset`)
               VALUES ($tag, $offset)
           """.update.run)
        yield ()

    def retrieve(tag: String): ConnectionIO[Option[Sequence]] =
      sql"""
           SELECT `offset`
             FROM tag_offsets
             WHERE tag = $tag
         """
        .query[Sequence]
        .option

    def update(tag: String, offset: Sequence): ConnectionIO[Unit] =
      for (_ <- sql"""
             UPDATE tag_offsets
               SET `offset` = $offset
               WHERE tag = $tag
           """.update.run)
        yield ()

  }
}
