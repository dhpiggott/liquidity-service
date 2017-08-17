// TODO: Remove when migration is complete
package com.dhpcs.liquidity.server

import java.time.Instant

import akka.Done
import akka.actor.{Actor, ActorSystem, ExtendedActorSystem, Props}
import akka.pattern.ask
import akka.persistence.PersistentActor
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.Timeout
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.persistence.zone._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.server.ProtoBindingBackedSerializer.AnyRefProtoBinding
import com.typesafe.config.ConfigFactory
import okio.ByteString

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}

object JournalMigration {

  implicit final val BigDecimalProtoBinding: ProtoBinding[BigDecimal, proto.persistence.LegacyBigDecimal, Any] =
    ProtoBinding.instance(
      bigDecimal =>
        proto.persistence.LegacyBigDecimal(
          bigDecimal.scale,
          com.google.protobuf.ByteString.copyFrom(bigDecimal.underlying().unscaledValue().toByteArray)
      ),
      (legacyBigDecimal, _) =>
        BigDecimal(
          BigInt(legacyBigDecimal.value.toByteArray),
          legacyBigDecimal.scale
      )
    )

  implicit final val LegacyMemberProtoBinding: ProtoBinding[Member, proto.persistence.LegacyMember, Any] =
    ProtoBinding.instance(
      member =>
        proto.persistence.LegacyMember(
          member.id.id.toInt,
          com.google.protobuf.ByteString.copyFrom(member.ownerPublicKeys.head.value.toByteArray),
          member.name,
          member.metadata
      ),
      (legacyMember, _) =>
        Member(
          MemberId(legacyMember.id.toString),
          Set(PublicKey(legacyMember.ownerPublicKey.toByteArray)),
          legacyMember.name,
          legacyMember.metadata
      )
    )

  implicit final val LegacyAccountProtoBinding: ProtoBinding[Account, proto.persistence.LegacyAccount, Any] =
    ProtoBinding.instance(
      account =>
        proto.persistence.LegacyAccount(
          account.id.id.toInt,
          account.ownerMemberIds.map(_.id.toInt).toSeq,
          account.name,
          account.metadata
      ),
      (legacyAccount, _) =>
        Account(
          AccountId(legacyAccount.id.toString),
          legacyAccount.ownerMemberIds.map(memberId => MemberId(memberId.toString)).toSet,
          legacyAccount.name,
          legacyAccount.metadata
      )
    )

  implicit final val LegacyTransactionProtoBinding
    : ProtoBinding[Transaction, proto.persistence.LegacyTransaction, Any] =
    ProtoBinding.instance(
      transaction =>
        proto.persistence.LegacyTransaction(
          transaction.id.id.toInt,
          transaction.from.id.toInt,
          transaction.to.id.toInt,
          Some(ProtoBinding[BigDecimal, proto.persistence.LegacyBigDecimal, Any].asProto(transaction.value)),
          transaction.creator.id.toInt,
          transaction.created,
          transaction.description,
          transaction.metadata
      ),
      (legacyTransaction, _) =>
        Transaction(
          TransactionId(legacyTransaction.id.toString),
          AccountId(legacyTransaction.from.toString),
          AccountId(legacyTransaction.to.toString),
          ProtoBinding[BigDecimal, proto.persistence.LegacyBigDecimal, Any].asScala(legacyTransaction.value.get)(()),
          MemberId(legacyTransaction.creator.toString),
          legacyTransaction.created,
          legacyTransaction.description,
          legacyTransaction.metadata
      )
    )

  implicit final val LegacyZoneProtoBinding: ProtoBinding[Zone, proto.persistence.LegacyZone, Any] =
    ProtoBinding.instance(
      zone =>
        proto.persistence.LegacyZone(
          zone.id.id,
          zone.equityAccountId.id.toInt,
          zone.members.values.map(ProtoBinding[Member, proto.persistence.LegacyMember, Any].asProto).toSeq,
          zone.accounts.values.map(ProtoBinding[Account, proto.persistence.LegacyAccount, Any].asProto).toSeq,
          zone.transactions.values
            .map(ProtoBinding[Transaction, proto.persistence.LegacyTransaction, Any].asProto)
            .toSeq,
          zone.created,
          zone.expires,
          zone.name,
          zone.metadata
      ),
      (legacyZone, _) =>
        Zone(
          ZoneId(legacyZone.id),
          AccountId(legacyZone.equityAccountId.toString),
          legacyZone.members
            .map(legacyMember => ProtoBinding[Member, proto.persistence.LegacyMember, Any].asScala(legacyMember)(()))
            .map(member => member.id -> member)
            .toMap,
          legacyZone.accounts
            .map(legacyAccount =>
              ProtoBinding[Account, proto.persistence.LegacyAccount, Any].asScala(legacyAccount)(()))
            .map(account => account.id -> account)
            .toMap,
          legacyZone.transactions
            .map(legacyTransaction =>
              ProtoBinding[Transaction, proto.persistence.LegacyTransaction, Any].asScala(legacyTransaction)(()))
            .map(transaction => transaction.id -> transaction)
            .toMap,
          legacyZone.created,
          legacyZone.expires,
          legacyZone.name,
          legacyZone.metadata
      )
    )

  class v3ZoneEventSerializer(system: ExtendedActorSystem)
      extends ProtoBindingBackedSerializer(
        system,
        protoBindings = Seq(
          AnyRefProtoBinding[proto.persistence.ZoneCreatedEvent, proto.persistence.ZoneCreatedEvent],
          AnyRefProtoBinding[proto.persistence.ZoneJoinedEvent, proto.persistence.ZoneJoinedEvent],
          AnyRefProtoBinding[proto.persistence.ZoneQuitEvent, proto.persistence.ZoneQuitEvent],
          AnyRefProtoBinding[proto.persistence.ZoneNameChangedEvent, proto.persistence.ZoneNameChangedEvent],
          AnyRefProtoBinding[proto.persistence.MemberCreatedEvent, proto.persistence.MemberCreatedEvent],
          AnyRefProtoBinding[proto.persistence.MemberUpdatedEvent, proto.persistence.MemberUpdatedEvent],
          AnyRefProtoBinding[proto.persistence.AccountCreatedEvent, proto.persistence.AccountCreatedEvent],
          AnyRefProtoBinding[proto.persistence.AccountUpdatedEvent, proto.persistence.AccountUpdatedEvent],
          AnyRefProtoBinding[proto.persistence.TransactionAddedEvent, proto.persistence.TransactionAddedEvent]
        ),
        identifier = 1474968907
      )

  private object WriterActor {
    def props(persistenceId: String): Props = Props(new WriterActor(persistenceId))
  }

  private class WriterActor(override val persistenceId: String) extends PersistentActor {

    override val journalPluginId = "cassandra-write-journal-v4"

    override def receiveRecover: Receive =
      Actor.emptyBehavior

    override def receiveCommand: Receive = {
      case event => persist(event)(_ => sender().!(Done))
    }
  }

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem(
      "liquidity",
      ConfigFactory
        .parseString(s"""
            |akka {
            |  actor {
            |    serializers {
            |      v3-zone-record = "com.dhpcs.liquidity.server.JournalMigration$$v3ZoneEventSerializer"
            |      v4-zone-record = "com.dhpcs.liquidity.server.serialization.ZoneRecordSerializer"
            |    }
            |    serialization-bindings {
            |      "com.dhpcs.liquidity.persistence.zone.ZoneRecord" = v4-zone-record
            |    }
            |    allow-java-serialization = off
            |  }
            |  persistence.journal.plugin = "cassandra-journal"
            |}
            |cassandra-journal.contact-points = ["cassandra"]
            |cassandra-journal-v3 = $${cassandra-journal}
            |cassandra-journal-v3 {
            |  keyspace = "liquidity_journal"
            |}
            |cassandra-read-journal-v3 = $${cassandra-query-journal}
            |cassandra-read-journal-v3 {
            |  write-plugin = cassandra-journal-v3
            |}
            |cassandra-journal-v4 = $${cassandra-journal}
            |cassandra-journal-v4 {
            |  keyspace = "liquidity_journal_v4"
            |}
            |cassandra-write-journal-v4 = $${cassandra-journal-v4}
          """.stripMargin)
        .withFallback(ConfigFactory.defaultReference())
        .resolve()
    )
    implicit val mat: ActorMaterializer       = ActorMaterializer()
    implicit val ec: ExecutionContextExecutor = ExecutionContext.global
    try {
      Await.result(for ((eventCount, zoneCount) <- migrateZoneEvents())
                     yield println(s"Wrote $eventCount events for $zoneCount zones"),
                   Duration.Inf); ()
    } finally {
      Await.result(
        system.terminate(),
        Duration.Inf
      ); ()
    }
  }

  private def migrateZoneEvents()(implicit system: ActorSystem, mat: Materializer): Future[(Int, Int)] = {
    val readJournal = PersistenceQuery(system)
      .readJournalFor[CassandraReadJournal]("cassandra-read-journal-v3")
    readJournal
      .currentPersistenceIds()
      .map(ZoneId.fromPersistenceId)
      .mapAsyncUnordered(sys.runtime.availableProcessors) { zoneId =>
        implicit val timeout: Timeout = Timeout(5.seconds)
        import system.dispatcher
        val persistenceId = zoneId.persistenceId
        val writerActor   = system.actorOf(WriterActor.props(persistenceId))
        val result =
          readJournal
            .currentEventsByPersistenceId(persistenceId, 0, Long.MaxValue)
            .mapAsync(1)(eventEnvelope =>
              (writerActor ? convertZoneEvent(eventEnvelope.event))
                .mapTo[Done]
                .map(_ => 1))
            .runFold(0)(_ + _)
            .map { eventCount =>
              println(s"Rewrote $eventCount events for $persistenceId")
              eventCount
            }
        result.onComplete(_ => system.stop(writerActor))
        result
      }
      .runFold((0, 0)) {
        case ((eventCount, zoneCount), zoneEventCount) =>
          (eventCount + zoneEventCount, zoneCount + 1)
      }
  }

  private def convertZoneEvent(event: Any): ZoneEventEnvelope = event match {
    case proto.persistence.ZoneCreatedEvent(timestamp, maybeLegacyZone) =>
      ZoneEventEnvelope(
        remoteAddress = None,
        publicKey = None,
        Instant.ofEpochMilli(timestamp),
        ZoneCreatedEvent(
          maybeLegacyZone
            .map(legacyZone => ProtoBinding[Zone, proto.persistence.LegacyZone, Any].asScala(legacyZone)(()))
            .get)
      )
    case proto.persistence.ZoneJoinedEvent(timestamp, _, publicKey) =>
      ZoneEventEnvelope(
        remoteAddress = None,
        publicKey = Some(PublicKey(ByteString.of(publicKey.toByteArray: _*))),
        Instant.ofEpochMilli(timestamp),
        ClientJoinedEvent(None)
      )
    case proto.persistence.ZoneQuitEvent(timestamp, _) =>
      ZoneEventEnvelope(
        remoteAddress = None,
        publicKey = None,
        Instant.ofEpochMilli(timestamp),
        ClientQuitEvent(None)
      )
    case proto.persistence.ZoneNameChangedEvent(timestamp, name) =>
      ZoneEventEnvelope(
        remoteAddress = None,
        publicKey = None,
        Instant.ofEpochMilli(timestamp),
        ZoneNameChangedEvent(name)
      )
    case proto.persistence.MemberCreatedEvent(timestamp, maybeLegacyMember) =>
      ZoneEventEnvelope(
        remoteAddress = None,
        publicKey = None,
        Instant.ofEpochMilli(timestamp),
        MemberCreatedEvent(
          maybeLegacyMember
            .map(legacyMember => ProtoBinding[Member, proto.persistence.LegacyMember, Any].asScala(legacyMember)(()))
            .get)
      )
    case proto.persistence.MemberUpdatedEvent(timestamp, maybeLegacyMember) =>
      ZoneEventEnvelope(
        remoteAddress = None,
        publicKey = None,
        Instant.ofEpochMilli(timestamp),
        MemberUpdatedEvent(
          maybeLegacyMember
            .map(legacyMember => ProtoBinding[Member, proto.persistence.LegacyMember, Any].asScala(legacyMember)(()))
            .get)
      )
    case proto.persistence.AccountCreatedEvent(timestamp, maybeLegacyAccount) =>
      ZoneEventEnvelope(
        remoteAddress = None,
        publicKey = None,
        Instant.ofEpochMilli(timestamp),
        AccountCreatedEvent(
          maybeLegacyAccount
            .map(legacyAccount =>
              ProtoBinding[Account, proto.persistence.LegacyAccount, Any].asScala(legacyAccount)(()))
            .get)
      )
    case proto.persistence.AccountUpdatedEvent(timestamp, maybeLegacyAccount) =>
      ZoneEventEnvelope(
        remoteAddress = None,
        publicKey = None,
        Instant.ofEpochMilli(timestamp),
        AccountUpdatedEvent(actingAs = None,
                            maybeLegacyAccount
                              .map(legacyAccount =>
                                ProtoBinding[Account, proto.persistence.LegacyAccount, Any].asScala(legacyAccount)(()))
                              .get)
      )
    case proto.persistence.TransactionAddedEvent(timestamp, maybeLegacyTransaction) =>
      ZoneEventEnvelope(
        remoteAddress = None,
        publicKey = None,
        Instant.ofEpochMilli(timestamp),
        TransactionAddedEvent(
          maybeLegacyTransaction
            .map(legacyTransaction =>
              ProtoBinding[Transaction, proto.persistence.LegacyTransaction, Any].asScala(legacyTransaction)(()))
            .get)
      )
  }
}
