package com.dhpcs.liquidity.server.actor

import java.net.InetAddress
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.UUID

import akka.actor.typed
import akka.actor.typed.ActorRefResolver
import akka.actor.typed.scaladsl.adapter._
import akka.testkit.TestProbe
import cats.data.Validated
import com.dhpcs.liquidity.actor.protocol.clientconnection._
import com.dhpcs.liquidity.actor.protocol.zonevalidator._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.server.InmemoryPersistenceTestFixtures
import com.dhpcs.liquidity.ws.protocol._
import org.scalactic.TripleEqualsSupport.Spread
import org.scalatest.{Inside, Outcome, fixture}

import scala.util.Random

class ZoneValidatorActorSpec
    extends fixture.FreeSpec
    with InmemoryPersistenceTestFixtures
    with Inside {

  private[this] val remoteAddress = InetAddress.getLoopbackAddress
  private[this] val publicKey = PublicKey(rsaPublicKey.getEncoded)

  override protected type FixtureParam =
    (TestProbe, ZoneId, typed.ActorRef[SerializableZoneValidatorMessage])

  override protected def withFixture(test: OneArgTest): Outcome = {
    val clientConnectionTestProbe = TestProbe()
    val zoneId = ZoneId(UUID.randomUUID().toString)
    val zoneValidator = system.spawnAnonymous(
      ZoneValidatorActor.shardingBehavior(entityId = zoneId.persistenceId))
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
            name = Some(
              Random.alphanumeric
                .take(ZoneCommand.MaximumTagLength + 1)
                .mkString
            ),
            metadata = None
          )
        )
        assert(
          expectResponse(fixture) === CreateZoneResponse(
            Validated.invalidNel(ZoneResponse.Error.tagLengthExceeded)))
      }
      "accepts it if valid" in { fixture =>
        createZone(fixture)
      }
      "accepts redeliveries" in { fixture =>
        createZone(fixture)
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
      "accepts redeliveries" in { fixture =>
        createZone(fixture)
        joinZone(fixture)
        joinZone(fixture, redelivery = true)
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
      "accepts redeliveries" in { fixture =>
        createZone(fixture)
        joinZone(fixture)
        quitZone(fixture)
        quitZone(fixture)
      }
    }
    "receiving change zone name commands" - {
      "rejects it if the zone has not been created" in { fixture =>
        sendCommand(fixture)(
          ChangeZoneNameCommand(None)
        )
        assert(
          expectResponse(fixture) === ChangeZoneNameResponse(
            Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist)))
      }
      "rejects it if the name is too long" in { fixture =>
        createZone(fixture)
        sendCommand(fixture)(
          ChangeZoneNameCommand(Some(Random.alphanumeric.take(161).mkString))
        )
        assert(
          expectResponse(fixture) === ChangeZoneNameResponse(
            Validated.invalidNel(ZoneResponse.Error.tagLengthExceeded)))
      }
      "accepts it if valid" in { fixture =>
        createZone(fixture)
        changeZoneName(fixture)
      }
      "accepts redeliveries" in { fixture =>
        createZone(fixture)
        changeZoneName(fixture)
        changeZoneName(fixture)
      }
    }
    "receiving create member commands" - {
      "rejects it if the zone has not been created" in { fixture =>
        sendCommand(fixture)(
          CreateMemberCommand(
            ownerPublicKeys = Set(publicKey),
            name = Some("Jenny"),
            metadata = None
          )
        )
        assert(
          expectResponse(fixture) === CreateMemberResponse(
            Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist)))
      }
      "rejects it if a public key of invalid type is given" in { fixture =>
        createZone(fixture)
        val keyPairGenerator = KeyPairGenerator.getInstance("DSA")
        keyPairGenerator.initialize(2048)
        sendCommand(fixture)(
          CreateMemberCommand(
            ownerPublicKeys = Set(
              PublicKey(keyPairGenerator.generateKeyPair().getPublic.getEncoded)
            ),
            name = Some("Jenny"),
            metadata = None
          )
        )
        assert(
          expectResponse(fixture) === CreateMemberResponse(
            Validated.invalidNel(ZoneResponse.Error.invalidPublicKeyType)))
      }
      "rejects it if a public key of invalid length is given" in { fixture =>
        createZone(fixture)
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(4096)
        sendCommand(fixture)(
          CreateMemberCommand(
            ownerPublicKeys = Set(
              PublicKey(keyPairGenerator.generateKeyPair().getPublic.getEncoded)
            ),
            name = Some("Jenny"),
            metadata = None
          )
        )
        assert(
          expectResponse(fixture) === CreateMemberResponse(
            Validated.invalidNel(ZoneResponse.Error.invalidPublicKeyLength)))
      }
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
      "rejects it if the zone has not been created" in { fixture =>
        sendCommand(fixture)(
          UpdateMemberCommand(
            Member(
              id = MemberId("1"),
              ownerPublicKeys = Set(publicKey),
              name = Some("Jenny"),
              metadata = None
            )
          )
        )
        assert(
          expectResponse(fixture) === UpdateMemberResponse(
            Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist)))
      }
      "rejects it if the member does not exist" in { fixture =>
        createZone(fixture)
        sendCommand(fixture)(
          UpdateMemberCommand(
            Member(
              id = MemberId("1"),
              ownerPublicKeys = Set(publicKey),
              name = Some("Jenny"),
              metadata = None
            )
          )
        )
        assert(
          expectResponse(fixture) === UpdateMemberResponse(
            Validated.invalidNel(ZoneResponse.Error.memberDoesNotExist)))
      }
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
      "rejects it if a public key of invalid type is given" in { fixture =>
        createZone(fixture)
        val member = createMember(fixture)
        val keyPairGenerator = KeyPairGenerator.getInstance("DSA")
        keyPairGenerator.initialize(2048)
        sendCommand(fixture)(
          UpdateMemberCommand(
            member.copy(
              ownerPublicKeys = Set(
                PublicKey(
                  keyPairGenerator.generateKeyPair().getPublic.getEncoded)
              )
            )
          )
        )
        assert(
          expectResponse(fixture) === UpdateMemberResponse(
            Validated.invalidNel(ZoneResponse.Error.invalidPublicKeyType)))
      }
      "rejects it if a public key of invalid length is given" in { fixture =>
        createZone(fixture)
        val member = createMember(fixture)
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(4096)
        sendCommand(fixture)(
          UpdateMemberCommand(
            member.copy(
              ownerPublicKeys = Set(
                PublicKey(
                  keyPairGenerator.generateKeyPair().getPublic.getEncoded)
              )
            )
          )
        )
        assert(
          expectResponse(fixture) === UpdateMemberResponse(
            Validated.invalidNel(ZoneResponse.Error.invalidPublicKeyLength)))
      }
      "accepts it if valid" in { fixture =>
        createZone(fixture)
        val member = createMember(fixture)
        updateMember(fixture, member)
      }
      "accepts redeliveries" in { fixture =>
        createZone(fixture)
        val member = createMember(fixture)
        updateMember(fixture, member)
        updateMember(fixture, member)
      }
    }
    "receiving create account commands" - {
      "rejects it if the zone has not been created" in { fixture =>
        sendCommand(fixture)(
          CreateAccountCommand(
            ownerMemberIds = Set(MemberId("1")),
            name = Some("Jenny's Account"),
            metadata = None
          )
        )
        assert(
          expectResponse(fixture) === CreateAccountResponse(
            Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist)))
      }
      "rejects it if an owner does not exist" in { fixture =>
        createZone(fixture)
        createMember(fixture)
        sendCommand(fixture)(
          CreateAccountCommand(
            ownerMemberIds = Set(MemberId("non-existent")),
            name = Some("Jenny's Account"),
            metadata = None
          )
        )
        assert(
          expectResponse(fixture) === CreateAccountResponse(
            Validated.invalidNel(
              ZoneResponse.Error.memberDoesNotExist(MemberId("non-existent")))))
      }
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
        createAccount(fixture, owner = member.id)
      }
    }
    "receiving update account commands" - {
      "rejects it if the zone has not been created" in { fixture =>
        sendCommand(fixture)(
          UpdateAccountCommand(
            actingAs = MemberId("1"),
            Account(
              id = AccountId("1"),
              ownerMemberIds = Set.empty,
              name = Some("Jenny's Account"),
              metadata = None
            )
          )
        )
        assert(
          expectResponse(fixture) === UpdateAccountResponse(
            Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist)))
      }
      "rejects it if the account does not exist" in { fixture =>
        createZone(fixture)
        val member = createMember(fixture)
        sendCommand(fixture)(
          UpdateAccountCommand(
            actingAs = member.id,
            Account(
              id = AccountId("1"),
              ownerMemberIds = Set(member.id),
              name = None,
              metadata = None
            )
          )
        )
        assert(
          expectResponse(fixture) === UpdateAccountResponse(
            Validated.invalidNel(ZoneResponse.Error.accountDoesNotExist)))
      }
      "rejects it if not from an owner" in { fixture =>
        createZone(fixture)
        val member = createMember(fixture)
        val account = createAccount(fixture, owner = member.id)
        sendCommand(fixture)(
          UpdateAccountCommand(
            actingAs = MemberId("non-existent"),
            account.copy(name = None)
          )
        )
        assert(
          expectResponse(fixture) === UpdateAccountResponse(
            Validated.invalidNel(ZoneResponse.Error.accountOwnerMismatch)))
      }
      "rejects it if the acting member is not owned by the requester" in {
        fixture =>
          createZone(fixture)
          val member = createMember(fixture)
          val account = createAccount(fixture, owner = member.id)
          val (clientConnectionTestProbe, zoneId, zoneValidator) = fixture
          clientConnectionTestProbe.send(
            zoneValidator.toUntyped,
            ZoneCommandEnvelope(
              clientConnectionTestProbe.ref,
              zoneId,
              remoteAddress,
              publicKey = PublicKey(Array.emptyByteArray),
              correlationId = 0,
              UpdateAccountCommand(
                actingAs = member.id,
                account.copy(name = None)
              )
            )
          )
          assert(
            expectResponse(fixture) === UpdateAccountResponse(
              Validated.invalidNel(ZoneResponse.Error.memberKeyMismatch)))
      }
      "rejects it if an owner does not exist" in { fixture =>
        createZone(fixture)
        val member = createMember(fixture)
        val account = createAccount(fixture, owner = member.id)
        sendCommand(fixture)(
          UpdateAccountCommand(
            actingAs = member.id,
            account.copy(ownerMemberIds = Set(MemberId("non-existent")))
          )
        )
        assert(
          expectResponse(fixture) === UpdateAccountResponse(
            Validated.invalidNel(
              ZoneResponse.Error.memberDoesNotExist(MemberId("non-existent")))))
      }
      "rejects it if no owners are given" in { fixture =>
        createZone(fixture)
        val member = createMember(fixture)
        val account = createAccount(fixture, owner = member.id)
        sendCommand(fixture)(
          UpdateAccountCommand(
            actingAs = member.id,
            account.copy(ownerMemberIds = Set.empty)
          )
        )
        assert(
          expectResponse(fixture) === UpdateAccountResponse(
            Validated.invalidNel(ZoneResponse.Error.noMemberIds)))
      }
      "accepts it if valid" in { fixture =>
        createZone(fixture)
        val member = createMember(fixture)
        val account = createAccount(fixture, owner = member.id)
        updateAccount(fixture, account)
      }
      "accepts redeliveries" in { fixture =>
        createZone(fixture)
        val member = createMember(fixture)
        val account = createAccount(fixture, owner = member.id)
        updateAccount(fixture, account)
        updateAccount(fixture, account)
      }
    }
    "receiving add transaction commands" - {
      "rejects it if the zone has not been created" in { fixture =>
        sendCommand(fixture)(
          AddTransactionCommand(
            actingAs = MemberId("0"),
            from = AccountId("0"),
            to = AccountId("1"),
            value = BigDecimal(5000),
            description = Some("Jenny's Lottery Win"),
            metadata = None
          )
        )
        assert(
          expectResponse(fixture) === AddTransactionResponse(
            Validated.invalidNel(ZoneResponse.Error.zoneDoesNotExist)))
      }
      "rejects it if the source account does not exist" in { fixture =>
        val zone = createZone(fixture)
        val member = createMember(fixture)
        val account = createAccount(fixture, owner = member.id)
        sendCommand(fixture)(
          AddTransactionCommand(
            actingAs = zone.accounts(zone.equityAccountId).ownerMemberIds.head,
            from = AccountId("non-existent"),
            to = account.id,
            value = BigDecimal(5000),
            description = Some("Jenny's Lottery Win"),
            metadata = None
          )
        )
        assert(
          expectResponse(fixture) === AddTransactionResponse(
            Validated.invalidNel(ZoneResponse.Error.sourceAccountDoesNotExist)))
      }
      "rejects it if the source account is not owned by the requesting member" in {
        fixture =>
          val zone = createZone(fixture)
          val member = createMember(fixture)
          val account = createAccount(fixture, owner = member.id)
          sendCommand(fixture)(
            AddTransactionCommand(
              actingAs = member.id,
              from = zone.equityAccountId,
              to = account.id,
              value = BigDecimal(5000),
              description = Some("Jenny's Lottery Win"),
              metadata = None
            )
          )
          assert(
            expectResponse(fixture) === AddTransactionResponse(
              Validated.invalidNel(ZoneResponse.Error.accountOwnerMismatch)))
      }
      "rejects it if the acting member is not owned by the requester" in {
        fixture =>
          val zone = createZone(fixture)
          val member = createMember(fixture)
          val account = createAccount(fixture, owner = member.id)
          val (clientConnectionTestProbe, zoneId, zoneValidator) = fixture
          clientConnectionTestProbe.send(
            zoneValidator.toUntyped,
            ZoneCommandEnvelope(
              clientConnectionTestProbe.ref,
              zoneId,
              remoteAddress,
              publicKey = PublicKey(Array.emptyByteArray),
              correlationId = 0,
              AddTransactionCommand(
                actingAs =
                  zone.accounts(zone.equityAccountId).ownerMemberIds.head,
                from = zone.equityAccountId,
                to = account.id,
                value = BigDecimal(5000),
                description = Some("Jenny's Lottery Win"),
                metadata = None
              )
            )
          )
          assert(
            expectResponse(fixture) === AddTransactionResponse(
              Validated.invalidNel(ZoneResponse.Error.memberKeyMismatch)))
      }
      "rejects it if the destination account does not exist" in { fixture =>
        val zone = createZone(fixture)
        sendCommand(fixture)(
          AddTransactionCommand(
            actingAs = zone.accounts(zone.equityAccountId).ownerMemberIds.head,
            from = zone.equityAccountId,
            to = AccountId("non-existent"),
            value = BigDecimal(5000),
            description = Some("Jenny's Lottery Win"),
            metadata = None
          )
        )
        assert(
          expectResponse(fixture) === AddTransactionResponse(Validated
            .invalidNel(ZoneResponse.Error.destinationAccountDoesNotExist)))
      }
      "rejects it if the source account is the same as the destination account" in {
        fixture =>
          val zone = createZone(fixture)
          sendCommand(fixture)(
            AddTransactionCommand(
              actingAs = zone.accounts(zone.equityAccountId).ownerMemberIds.head,
              from = zone.equityAccountId,
              to = zone.equityAccountId,
              value = BigDecimal(5000),
              description = Some("Jenny's Lottery Win"),
              metadata = None
            )
          )
          assert(
            expectResponse(fixture) === AddTransactionResponse(Validated
              .invalidNel(ZoneResponse.Error.reflexiveTransaction)))
      }
      "rejects it if the value is negative" in { fixture =>
        val zone = createZone(fixture)
        val member = createMember(fixture)
        val account = createAccount(fixture, owner = member.id)
        sendCommand(fixture)(
          AddTransactionCommand(
            actingAs = zone.accounts(zone.equityAccountId).ownerMemberIds.head,
            from = zone.equityAccountId,
            to = account.id,
            value = BigDecimal(-5000),
            description = Some("Jenny's Lottery Win"),
            metadata = None
          )
        )
        assert(
          expectResponse(fixture) === AddTransactionResponse(
            Validated.invalidNel(ZoneResponse.Error.negativeTransactionValue)))
      }
      "rejects it if the source account has an insufficient balance" in {
        fixture =>
          val zone = createZone(fixture)
          val member = createMember(fixture)
          val account = createAccount(fixture, owner = member.id)
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
        val account = createAccount(fixture, owner = member.id)
        addTransaction(fixture, zone, to = account.id)
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
        name = Some("Dave's Game"),
        metadata = None
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
            ownerMemberIds = Set(equityAccountOwner.id),
            name = None,
            metadata = None
          )
        )
        assert(
          equityAccountOwner === Member(
            equityAccountOwner.id,
            Set(publicKey),
            name = Some("Dave"),
            metadata = None
          )
        )
        assert(
          zone.created === Spread(
            pivot = System.currentTimeMillis(),
            tolerance = 5000L
          )
        )
        assert(
          zone.expires === zone.created + java.time.Duration
            .ofDays(30)
            .toMillis
        )
        assert(zone.transactions === Map.empty)
        assert(zone.name === Some("Dave's Game"))
        assert(zone.metadata === None)
        zone
    }
  }

  private[this] def joinZone(fixture: FixtureParam,
                             redelivery: Boolean = false): Unit = {
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
            ownerMemberIds = Set(equityAccountOwner.id),
            name = None,
            metadata = None
          )
        )
        assert(
          equityAccountOwner === Member(
            equityAccountOwner.id,
            Set(publicKey),
            name = Some("Dave"),
            metadata = None
          )
        )
        assert(
          zone.created === Spread(
            pivot = System.currentTimeMillis(),
            tolerance = 5000L
          )
        )
        assert(
          zone.expires === zone.created + java.time.Duration
            .ofDays(30)
            .toMillis
        )
        assert(zone.transactions === Map.empty)
        assert(zone.name === Some("Dave's Game"))
        assert(zone.metadata === None)
        assert(
          connectedClients === Map(ActorRefResolver(system.toTyped)
            .toSerializationFormat(clientConnectionTestProbe.ref) -> publicKey))
    }
    if (!redelivery)
      assert(
        expectNotification(fixture) === ClientJoinedNotification(
          connectionId = ActorRefResolver(system.toTyped)
            .toSerializationFormat(clientConnectionTestProbe.ref),
          publicKey
        )
      )
    ()
  }

  private[this] def quitZone(fixture: FixtureParam): Unit = {
    sendCommand(fixture)(
      QuitZoneCommand
    )
    assert(expectResponse(fixture) === QuitZoneResponse(Validated.Valid(())))
    ()
  }

  private[this] def changeZoneName(fixture: FixtureParam): Unit = {
    sendCommand(fixture)(
      ChangeZoneNameCommand(None)
    )
    assert(
      expectResponse(fixture) === ChangeZoneNameResponse(Validated.Valid(())))
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
    assert(
      expectResponse(fixture) === UpdateMemberResponse(Validated.Valid(())))
    ()
  }

  private[this] def createAccount(fixture: FixtureParam,
                                  owner: MemberId): Account = {
    sendCommand(fixture)(
      CreateAccountCommand(
        ownerMemberIds = Set(owner),
        name = Some("Jenny's Account"),
        metadata = None
      )
    )
    inside(expectResponse(fixture)) {
      case CreateAccountResponse(Validated.Valid(account)) =>
        assert(account.ownerMemberIds === Set(owner))
        assert(account.name === Some("Jenny's Account"))
        account
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
    assert(
      expectResponse(fixture) === UpdateAccountResponse(Validated.Valid(())))
    ()
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
    }
    ()
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
