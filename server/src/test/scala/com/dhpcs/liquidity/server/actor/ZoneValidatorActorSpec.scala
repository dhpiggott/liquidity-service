package com.dhpcs.liquidity.server.actor

import java.net.InetAddress
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

import akka.testkit.TestProbe
import akka.typed
import akka.typed.cluster.ActorRefResolver
import akka.typed.scaladsl.adapter._
import cats.data.Validated
import com.dhpcs.liquidity.actor.protocol.clientconnection._
import com.dhpcs.liquidity.actor.protocol.zonevalidator._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.server.{InmemoryPersistenceTestFixtures, TestKit}
import com.dhpcs.liquidity.ws.protocol._
import org.scalactic.TripleEqualsSupport.Spread
import org.scalatest.{Inside, Outcome, fixture}

import scala.util.Random

class ZoneValidatorActorSpec
    extends fixture.FreeSpec
    with InmemoryPersistenceTestFixtures
    with Inside {

  private[this] val remoteAddress = InetAddress.getLoopbackAddress
  private[this] val publicKey = PublicKey(TestKit.rsaPublicKey.getEncoded)

  override protected type FixtureParam =
    (TestProbe, ZoneId, typed.ActorRef[SerializableZoneValidatorMessage])

  override protected def withFixture(test: OneArgTest): Outcome = {
    val clientConnectionTestProbe = TestProbe()
    val zoneId = ZoneId(UUID.randomUUID.toString)
    val zoneValidator = system.spawn(ZoneValidatorActor.shardingBehavior,
                                     name = zoneId.persistenceId)
    try withFixture(
      test.toNoArgTest((clientConnectionTestProbe, zoneId, zoneValidator))
    )
    finally system.stop(zoneValidator.toUntyped)
  }

  "A ZoneValidatorActor" - {
    "receiving create zone commands" - {
      "rejects it if the name is too long" in { fixture =>
        sendCommand(fixture)(
          CreateZoneCommand(
            equityOwnerPublicKey = publicKey,
            equityOwnerName = Some("Dave"),
            equityOwnerMetadata = None,
            equityAccountName = None,
            equityAccountMetadata = None,
            name = Some(Random.nextString(ZoneCommand.MaximumTagLength + 1))
          )
        )
        assert(
          expectResponse(fixture) === CreateZoneResponse(
            Validated.invalidNel(ZoneResponse.Error.tagLengthExceeded)))
      }
      "accepts it if valid" in { fixture =>
        createZone(fixture)
      }
    }
    "receiving join zone commands" - {
      "rejects it if the zone has not been created" in { fixture =>
        sendCommand(fixture)(
          JoinZoneCommand
        )
        assert(
          expectResponse(fixture) === JoinZoneResponse(
            Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist)))
      }
      "accepts it if valid" in { fixture =>
        createZone(fixture)
        joinZone(fixture)
      }
    }
    "receiving quit zone commands" - {
      "rejects it if the zone has not been created" in { fixture =>
        sendCommand(fixture)(
          QuitZoneCommand
        )
        assert(
          expectResponse(fixture) === QuitZoneResponse(
            Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist)))
      }
      "accepts it if valid" in { fixture =>
        createZone(fixture)
        joinZone(fixture)
        quitZone(fixture)
      }
    }
    "receiving change zone name commands" - {
      "rejects it if the name is too long" in { fixture =>
        createZone(fixture)
        sendCommand(fixture)(
          ChangeZoneNameCommand(Some(Random.nextString(161)))
        )
        assert(
          expectResponse(fixture) === ChangeZoneNameResponse(
            Validated.invalidNel(ZoneResponse.Error.tagLengthExceeded)))
      }
      "accepts it if valid" in { fixture =>
        createZone(fixture)
        changeZoneName(fixture)
      }
    }
    "receiving create member commands" - {
      "rejects it if no owners are given" in { fixture =>
        createZone(fixture)
        sendCommand(fixture)(
          CreateMemberCommand(
            ownerPublicKeys = Set.empty,
            name = Some("Jenny"),
            metadata = None
          )
        )
        assert(
          expectResponse(fixture) === CreateMemberResponse(
            Validated.invalidNel(ZoneResponse.Error.noPublicKeys)))
      }
      "accepts it if valid" in { fixture =>
        createZone(fixture)
        createMember(fixture)
      }
    }
    "receiving update member commands" - {
      "rejects it if not from an owner" in { fixture =>
        createZone(fixture)
        val member = createMember(fixture)
        val (clientConnectionTestProbe, zoneId, zoneValidator) = fixture
        clientConnectionTestProbe.send(
          zoneValidator.toUntyped,
          ZoneCommandEnvelope(
            clientConnectionTestProbe.ref,
            zoneId,
            remoteAddress,
            publicKey = PublicKey(Array.emptyByteArray),
            correlationId = 0,
            UpdateMemberCommand(member.copy(name = None))
          )
        )
        assert(
          expectResponse(fixture) === UpdateMemberResponse(
            Validated.invalidNel(ZoneResponse.Error.memberKeyMismatch)))
      }
      "accepts it if valid" in { fixture =>
        createZone(fixture)
        val member = createMember(fixture)
        updateMember(fixture, member)
      }
    }
    "receiving create account commands" - {
      "rejects it if no owners are given" in { fixture =>
        createZone(fixture)
        createMember(fixture)
        sendCommand(fixture)(
          CreateAccountCommand(
            ownerMemberIds = Set.empty,
            name = Some("Jenny's Account"),
            metadata = None
          )
        )
        assert(
          expectResponse(fixture) === CreateAccountResponse(
            Validated.invalidNel(ZoneResponse.Error.noMemberIds)))
      }
      "accepts it if valid" in { fixture =>
        createZone(fixture)
        val member = createMember(fixture)
        createAccount(fixture, member.id)
      }
    }
    "receiving update account commands" - {
      "rejects it if not from an owner" in { fixture =>
        createZone(fixture)
        val member = createMember(fixture)
        val account = createAccount(fixture, member.id)
        sendCommand(fixture)(
          UpdateAccountCommand(
            actingAs = MemberId(""),
            account.copy(name = None)
          )
        )
        assert(
          expectResponse(fixture) === UpdateAccountResponse(
            Validated.invalidNel(ZoneResponse.Error.accountOwnerMismatch)))
      }
      "accepts it if valid" in { fixture =>
        createZone(fixture)
        val member = createMember(fixture)
        val account = createAccount(fixture, member.id)
        updateAccount(fixture, account)
      }
    }
    "receiving add transaction commands" - {
      "rejects it if source has insufficient credit" in { fixture =>
        val zone = createZone(fixture)
        val member = createMember(fixture)
        val account = createAccount(fixture, member.id)
        sendCommand(fixture)(
          AddTransactionCommand(
            actingAs = member.id,
            from = account.id,
            to = zone.equityAccountId,
            value = BigDecimal(5000),
            description = Some("Jenny's Lottery Win"),
            metadata = None
          )
        )
        assert(
          expectResponse(fixture) === AddTransactionResponse(
            Validated.invalidNel(ZoneResponse.Error.insufficientBalance)))
      }
      "accepts it if valid" in { fixture =>
        val zone = createZone(fixture)
        val member = createMember(fixture)
        val account = createAccount(fixture, member.id)
        addTransaction(fixture, zone, account.id)
      }
    }
  }

  private[this] def createZone(fixture: FixtureParam): Zone = {
    sendCommand(fixture)(
      CreateZoneCommand(
        equityOwnerPublicKey = publicKey,
        equityOwnerName = Some("Dave"),
        equityOwnerMetadata = None,
        equityAccountName = None,
        equityAccountMetadata = None,
        name = Some("Dave's Game")
      )
    )
    inside(expectResponse(fixture)) {
      case CreateZoneResponse(Validated.Valid(zone)) =>
        assert(zone.accounts.size === 1)
        assert(zone.members.size === 1)
        val equityAccount = zone.accounts(zone.equityAccountId)
        val equityAccountOwner = zone.members(equityAccount.ownerMemberIds.head)
        assert(
          equityAccount === Account(
            equityAccount.id,
            ownerMemberIds = Set(equityAccountOwner.id)))
        assert(
          equityAccountOwner === Member(equityAccountOwner.id,
                                        Set(publicKey),
                                        name = Some("Dave")))
        assert(
          zone.created === Spread(pivot = Instant.now().toEpochMilli,
                                  tolerance = 5000L))
        assert(
          zone.expires === Spread(
            pivot = Instant.now().plus(7, ChronoUnit.DAYS).toEpochMilli,
            tolerance = 5000L))
        assert(zone.transactions === Map.empty)
        assert(zone.name === Some("Dave's Game"))
        assert(zone.metadata === None)
        zone
    }
  }

  private[this] def joinZone(fixture: FixtureParam): Unit = {
    val (clientConnectionTestProbe, _, _) = fixture
    sendCommand(fixture)(
      JoinZoneCommand
    )
    inside(expectResponse(fixture)) {
      case JoinZoneResponse(Validated.Valid((zone, connectedClients))) =>
        assert(zone.accounts.size === 1)
        assert(zone.members.size === 1)
        val equityAccount = zone.accounts(zone.equityAccountId)
        val equityAccountOwner = zone.members(equityAccount.ownerMemberIds.head)
        assert(
          equityAccount === Account(
            equityAccount.id,
            ownerMemberIds = Set(equityAccountOwner.id)))
        assert(
          equityAccountOwner === Member(equityAccountOwner.id,
                                        Set(publicKey),
                                        name = Some("Dave")))
        assert(
          zone.created === Spread(pivot = Instant.now().toEpochMilli,
                                  tolerance = 5000L))
        assert(
          zone.expires === Spread(
            pivot = Instant.now().plus(7, ChronoUnit.DAYS).toEpochMilli,
            tolerance = 5000L))
        assert(zone.transactions === Map.empty)
        assert(zone.name === Some("Dave's Game"))
        assert(zone.metadata === None)
        assert(
          connectedClients === Map(ActorRefResolver(system.toTyped)
            .toSerializationFormat(clientConnectionTestProbe.ref) -> publicKey))
    }
    assert(
      expectNotification(fixture) === ClientJoinedNotification(
        connectionId = ActorRefResolver(system.toTyped)
          .toSerializationFormat(clientConnectionTestProbe.ref),
        publicKey
      )
    ); ()
  }

  private[this] def quitZone(fixture: FixtureParam): Unit = {
    sendCommand(fixture)(
      QuitZoneCommand
    )
    assert(expectResponse(fixture) === QuitZoneResponse(Validated.Valid(())));
    ()
  }

  private[this] def changeZoneName(fixture: FixtureParam): Unit = {
    sendCommand(fixture)(
      ChangeZoneNameCommand(None)
    )
    assert(
      expectResponse(fixture) === ChangeZoneNameResponse(Validated.Valid(())));
    ()
  }

  private[this] def createMember(fixture: FixtureParam): Member = {
    sendCommand(fixture)(
      CreateMemberCommand(
        ownerPublicKeys = Set(publicKey),
        name = Some("Jenny"),
        metadata = None
      )
    )
    inside(expectResponse(fixture)) {
      case CreateMemberResponse(Validated.Valid(member)) =>
        assert(member.ownerPublicKeys === Set(publicKey))
        assert(member.name === Some("Jenny"))
        member
    }
  }

  private[this] def updateMember(fixture: FixtureParam,
                                 member: Member): Unit = {
    sendCommand(fixture)(
      UpdateMemberCommand(member.copy(name = None))
    )
    inside(expectResponse(fixture)) {
      case UpdateMemberResponse(Validated.Valid(())) => ()
    }
  }

  private[this] def updateAccount(fixture: FixtureParam,
                                  account: Account): Unit = {
    sendCommand(fixture)(
      UpdateAccountCommand(
        actingAs = account.ownerMemberIds.head,
        account.copy(name = None)
      )
    )
    inside(expectResponse(fixture)) {
      case UpdateAccountResponse(Validated.Valid(())) => ()
    }
  }

  private[this] def createAccount(fixture: FixtureParam,
                                  memberId: MemberId): Account = {
    sendCommand(fixture)(
      CreateAccountCommand(
        ownerMemberIds = Set(memberId),
        name = Some("Jenny's Account"),
        metadata = None
      )
    )
    inside(expectResponse(fixture)) {
      case CreateAccountResponse(Validated.Valid(account)) =>
        assert(account.ownerMemberIds === Set(memberId))
        assert(account.name === Some("Jenny's Account"))
        account
    }
  }

  private[this] def addTransaction(fixture: FixtureParam,
                                   zone: Zone,
                                   to: AccountId): Unit = {
    sendCommand(fixture)(
      AddTransactionCommand(
        actingAs = zone.accounts(zone.equityAccountId).ownerMemberIds.head,
        from = zone.equityAccountId,
        to = to,
        value = BigDecimal(5000),
        description = Some("Jenny's Lottery Win"),
        metadata = None
      )
    )
    inside(expectResponse(fixture)) {
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
        ()
    }
  }

  private[this] def sendCommand(fixture: FixtureParam)(
      zoneCommand: ZoneCommand): Unit = {
    val (clientConnectionTestProbe, zoneId, zoneValidator) = fixture
    clientConnectionTestProbe.send(
      zoneValidator.toUntyped,
      ZoneCommandEnvelope(
        clientConnectionTestProbe.ref,
        zoneId,
        remoteAddress,
        publicKey,
        correlationId = 0,
        zoneCommand
      )
    )
  }

  private[this] def expectResponse(fixture: FixtureParam): ZoneResponse = {
    val (clientConnectionTestProbe, _, _) = fixture
    val responseWithIds =
      clientConnectionTestProbe.expectMsgType[ZoneResponseEnvelope]
    assert(responseWithIds.correlationId === 0)
    responseWithIds.zoneResponse
  }

  private[this] def expectNotification(
      fixture: FixtureParam): ZoneNotification = {
    val (clientConnectionTestProbe, _, _) = fixture
    val notificationWithIds =
      clientConnectionTestProbe.expectMsgType[ZoneNotificationEnvelope]
    notificationWithIds.zoneNotification
  }
}
