package com.dhpcs.liquidity.server.actor

import java.net.InetAddress
import java.time.Instant

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{Behavior, PostStop}
import akka.persistence.query.Sequence
import akka.persistence.query.scaladsl.{EventsByTagQuery, ReadJournal}
import akka.stream.scaladsl.{Keep, Merge, RestartSource, Sink, Source}
import akka.stream.{KillSwitches, Materializer}
import cats.effect.IO
import cats.instances.list._
import cats.syntax.applicative._
import cats.syntax.traverse._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.persistence.EventTags
import com.dhpcs.liquidity.persistence.zone._
import com.dhpcs.liquidity.server.SqlBindings._
import doobie._
import doobie.implicits._

import scala.concurrent.duration._

object ZoneAnalyticsActor {

  sealed abstract class ZoneAnalyticsMessage
  case object StopZoneAnalytics extends ZoneAnalyticsMessage

  def singletonBehavior(readJournal: ReadJournal with EventsByTagQuery,
                        analyticsTransactor: Transactor[IO])(
      implicit mat: Materializer): Behavior[ZoneAnalyticsMessage] =
    Behaviors.setup { context =>
      val killSwitch = RestartSource
        .onFailuresWithBackoff(minBackoff = 3.seconds,
                               maxBackoff = 30.seconds,
                               randomFactor = 0.2)(
          () =>
            Source.combine(
              Source
                .fromFuture(
                  (for {
                    maybePreviousOffset <- TagOffsetsStore.retrieve(
                      EventTags.ZoneEventTag)
                    previousOffset <- maybePreviousOffset match {
                      case None =>
                        val firstOffset = Sequence(0)
                        for (_ <- TagOffsetsStore.insert(EventTags.ZoneEventTag,
                                                         firstOffset))
                          yield firstOffset
                      case Some(previousOffset) =>
                        previousOffset.pure[ConnectionIO]
                    }
                  } yield previousOffset)
                    .transact(analyticsTransactor)
                    .unsafeToFuture()
                )
                .flatMapConcat(readJournal.eventsByTag(EventTags.ZoneEventTag,
                                                       _))
                .mapAsync(1) { eventEnvelope =>
                  val zoneId =
                    ZoneId.fromPersistenceId(eventEnvelope.persistenceId)
                  val zoneEventEnvelope =
                    eventEnvelope.event.asInstanceOf[ZoneEventEnvelope]
                  val eventOffset = eventEnvelope.offset.asInstanceOf[Sequence]
                  (for {
                    _ <- projectEvent(zoneId, zoneEventEnvelope)
                    _ <- TagOffsetsStore.update(EventTags.ZoneEventTag,
                                                eventOffset)
                  } yield eventOffset)
                    .transact(analyticsTransactor)
                    .unsafeToFuture()
                }
                .zipWithIndex
                .groupedWithin(n = 1000, d = 30.seconds)
                .map { group =>
                  val (offset, index) = group.last
                  context.log.info(s"Projected ${group.size} zone events " +
                    s"(total: ${index + 1}, offset: ${offset.value})")
                },
              Source
                .tick(0.hours, 24.hours, ())
                .mapAsync(1)(_ =>
                  closeStaleConnections
                    .transact(analyticsTransactor)
                    .unsafeToFuture())
                .map(count =>
                  context.log.info(s"Closed $count stale connections"))
            )(Merge(_))
        )
        .viaMat(KillSwitches.single)(Keep.right)
        .to(Sink.ignore)
        .run()

      Behaviors.receiveMessage[ZoneAnalyticsMessage] {
        case StopZoneAnalytics =>
          Behaviors.stopped

      } receiveSignal {
        case (_, PostStop) =>
          killSwitch.shutdown()
          Behaviors.same
      }
    }

  private[this] def projectEvent(
      zoneId: ZoneId,
      zoneEventEnvelope: ZoneEventEnvelope): ConnectionIO[Unit] =
    for {
      _ <- zoneEventEnvelope.zoneEvent match {
        case ZoneEvent.Empty =>
          ().pure[ConnectionIO]

        case ZoneCreatedEvent(zone) =>
          ZoneStore.insert(zone)

        case ClientJoinedEvent(maybeActorRef) =>
          ClientSessionsStore.insert(zoneId,
                                     zoneEventEnvelope.remoteAddress,
                                     maybeActorRef,
                                     zoneEventEnvelope.publicKey,
                                     joined = zoneEventEnvelope.timestamp)

        case ClientQuitEvent(maybeActorRef) =>
          maybeActorRef match {
            case None =>
              ().pure[ConnectionIO]

            case Some(actorRef) =>
              for {
                previousSessionId <- ClientSessionsStore.retrieve(zoneId,
                                                                  actorRef)
                _ <- ClientSessionsStore.update(previousSessionId,
                                                quit =
                                                  zoneEventEnvelope.timestamp)
              } yield ()
          }

        case ZoneNameChangedEvent(name) =>
          ZoneNameChangeStore.insert(zoneId,
                                     name,
                                     changed = zoneEventEnvelope.timestamp)

        case MemberCreatedEvent(member) =>
          MembersStore.insert(zoneId,
                              member,
                              created = zoneEventEnvelope.timestamp)

        case MemberUpdatedEvent(member) =>
          MemberUpdatesStore.insert(zoneId,
                                    member,
                                    updated = zoneEventEnvelope.timestamp)

        case AccountCreatedEvent(account) =>
          AccountsStore.insert(zoneId,
                               account,
                               created = zoneEventEnvelope.timestamp,
                               balance = BigDecimal(0))

        case AccountUpdatedEvent(_, account) =>
          AccountUpdatesStore.insert(zoneId,
                                     account,
                                     updated = zoneEventEnvelope.timestamp)

        case TransactionAddedEvent(transaction) =>
          for {
            _ <- TransactionsStore.insert(zoneId, transaction)
            sourceBalance <- AccountsStore.retrieveBalance(zoneId,
                                                           transaction.from)
            _ <- AccountsStore.updateBalance(zoneId,
                                             transaction.from,
                                             sourceBalance - transaction.value)
            destinationBalance <- AccountsStore.retrieveBalance(zoneId,
                                                                transaction.to)
            _ <- AccountsStore.updateBalance(
              zoneId,
              transaction.to,
              destinationBalance + transaction.value)
          } yield ()
      }
      _ <- ZoneStore.update(zoneId, modified = zoneEventEnvelope.timestamp)
    } yield ()

  private[this] def closeStaleConnections: ConnectionIO[Int] =
    sql"""
          UPDATE client_sessions
            SET quit = joined
            WHERE quit IS NULL
      """.update.run

  object DevicesStore {

    def upsert(publicKey: PublicKey, created: Instant): ConnectionIO[Unit] =
      for {
        exists <- sql"""
                SELECT 1 FROM devices
                  WHERE fingerprint = ${publicKey.fingerprint}
          """
          .query[Int]
          .option
          .map(_.isDefined)
        _ <- if (exists)
          ().pure[ConnectionIO]
        else
          for (_ <- sql"""
           INSERT INTO devices (fingerprint, public_key, created)
             VALUES (${publicKey.fingerprint}, $publicKey, $created);
              """.update.run) yield ()
      } yield ()

  }

  object ZoneStore {

    def insert(zone: Zone): ConnectionIO[Unit] =
      for {
        _ <- sql"""
           INSERT INTO zones (zone_id, equity_account_id, created, expires, metadata)
             VALUES (${zone.id}, ${zone.equityAccountId}, ${zone.created}, ${zone.expires}, ${zone.metadata})
          """.update.run
        _ <- ZoneNameChangeStore.insert(zone.id, zone.name, zone.created)
        _ <- zone.members.values.toList
          .map(MembersStore.insert(zone.id, _, zone.created))
          .sequence
        _ <- zone.accounts.values.toList
          .map(AccountsStore.insert(zone.id, _, zone.created, BigDecimal(0)))
          .sequence
        _ <- zone.transactions.values.toList
          .map(TransactionsStore.insert(zone.id, _))
          .sequence
      } yield ()

    def update(zoneId: ZoneId, modified: Instant): ConnectionIO[Unit] =
      for (_ <- sql"""
             UPDATE zones
               SET modified = $modified
               WHERE zone_id = $zoneId
           """.update.run)
        yield ()

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
          .map(MemberOwnersStore.insert(updateId, updated, _))
          .toList
          .sequence
      } yield ()

    object MemberOwnersStore {

      def insert(updateId: Long,
                 updated: Instant,
                 publicKey: PublicKey): ConnectionIO[Unit] =
        for {
          _ <- DevicesStore.upsert(publicKey, updated)
          _ <- sql"""
           INSERT INTO member_owners (update_id, fingerprint)
             VALUES ($updateId, ${publicKey.fingerprint})
            """.update.run
        } yield ()

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

    def retrieveBalance(zoneId: ZoneId,
                        accountId: AccountId): ConnectionIO[BigDecimal] =
      sql"""
           SELECT balance FROM accounts
             WHERE zone_id = $zoneId AND account_id = $accountId
         """
        .query[BigDecimal]
        .unique

    def updateBalance(zoneId: ZoneId,
                      accountId: AccountId,
                      balance: BigDecimal): ConnectionIO[Unit] =
      for (_ <- sql"""
             UPDATE accounts
               SET balance = $balance
               WHERE zone_id = $zoneId AND account_id = $accountId
           """.update.run)
        yield ()

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

    object AccountOwnersStore {

      def insert(updateId: Long, memberId: MemberId): ConnectionIO[Unit] =
        for (_ <- sql"""
               INSERT INTO account_owners (update_id, member_id)
                 VALUES ($updateId, $memberId)
             """.update.run)
          yield ()

    }
  }

  object TransactionsStore {

    def insert(zoneId: ZoneId, transaction: Transaction): ConnectionIO[Unit] =
      for (_ <- sql"""
             INSERT INTO transactions (zone_id, transaction_id, `from`, `to`, `value`, creator, created, description, metadata)
               VALUES ($zoneId, ${transaction.id}, ${transaction.from}, ${transaction.to}, ${transaction.value}, ${transaction.creator}, ${transaction.created}, ${transaction.description}, ${transaction.metadata})
           """.update.run) yield ()

  }

  object ClientSessionsStore {

    def insert(zoneId: ZoneId,
               remoteAddress: Option[InetAddress],
               actorRef: Option[String],
               publicKey: Option[PublicKey],
               joined: Instant): ConnectionIO[Unit] =
      for {
        _ <- publicKey.fold(().pure[ConnectionIO])(
          DevicesStore.upsert(_, joined)
        )
        _ <- sql"""
           INSERT INTO client_sessions (zone_id, remote_address, actor_ref, fingerprint, joined)
             VALUES ($zoneId, $remoteAddress, $actorRef, ${publicKey.map(
          _.fingerprint)}, $joined)
          """.update.run
      } yield ()

    def retrieve(zoneId: ZoneId, actorRef: String): ConnectionIO[Long] =
      sql"""
           SELECT session_id FROM client_sessions
             WHERE zone_id = $zoneId AND actor_ref = $actorRef AND quit IS NULL
             ORDER BY session_id
             LIMIT 1
         """
        .query[Long]
        .unique

    def update(sessionId: Long, quit: Instant): ConnectionIO[Unit] =
      for (_ <- sql"""
             UPDATE client_sessions
               SET quit = $quit
               WHERE session_id = $sessionId
           """.update.run)
        yield ()

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
