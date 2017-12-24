package com.dhpcs.liquidity.server

import java.net.InetAddress
import java.time.Instant
import java.util.UUID

import akka.NotUsed
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.headers.`Remote-Address`
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.model.{ContentType, RemoteAddress, StatusCodes}
import akka.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import akka.stream.scaladsl.{Flow, Source}
import akka.typed.cluster.ActorRefResolver
import akka.typed.scaladsl.adapter._
import com.dhpcs.liquidity.actor.protocol.ProtoBindings._
import com.dhpcs.liquidity.actor.protocol.clientmonitor._
import com.dhpcs.liquidity.actor.protocol.zonemonitor._
import com.dhpcs.liquidity.model.ProtoBindings._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.persistence.zone._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.server.HttpController.EventEnvelope
import com.dhpcs.liquidity.server.HttpControllerSpec._
import com.typesafe.config.{Config, ConfigFactory}
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport._
import okio.ByteString
import org.scalatest.FreeSpec
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration._

object HttpControllerSpec {

  private val publicKey = PublicKey(ByteString.decodeBase64(
    "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA1V7/LB8zmkptcEH1/b6hUjdvzRW47/ZadR189o1EizOulLXRpWTgarXQ9bMcP8/exkHO/TKPPmRj/n206ydApG2lsq2px7lhOJnheQzGf8A/X/IpDOGL0sP4e23EV8MaxocsbuzGWrqM6z478L/+Qk1ntG7DmOTReSfWpgQ70IzVTgnq9fUqP+qu6/3qSmT4JMFE0YBYfCCtiMYrGN2LoQ0sq9peapuguxtCOIoOXAlo4UsnbN6KZrr1ggEIfOwUfSgoOpZ6andxwPh9M7f3AdD5RLneounQBz7bX5TKvICZz0PL3SkBxpBX0qENZtxnnPpgy15AeSTVVTDHUFhu2QIDAQAB"))
  private val zoneId = ZoneId("32824da3-094f-45f0-9b35-23b7827547c6")
  private val created = 1514156286183L
  private val equityAccountId = AccountId(0.toString)
  private val equityAccountOwnerId = MemberId(0.toString)
  private val zone = Zone(
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

}

class HttpControllerSpec
    extends FreeSpec
    with HttpController
    with ScalatestRouteTest {

  override def testConfig: Config = ConfigFactory.defaultReference()

  "The HttpController" - {
    "provides version information" in {
      val getRequest = RequestBuilding.Get("/version")
      getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
        assert(status === StatusCodes.OK)
        assert(contentType === ContentType(`application/json`))
        val keys = entityAs[JsObject].value.keySet
        assert(keys.contains("version"))
        assert(keys.contains("builtAtString"))
        assert(keys.contains("builtAtMillis"))
      }
    }
    "provides diagnostic information" - {
      "for events" in {
        val getRequest =
          RequestBuilding.Get(s"/diagnostics/events/zone-${UUID.randomUUID()}")
        getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
          assert(status === StatusCodes.OK)
          assert(contentType === ContentType(`application/json`))
          assert(
            entityAs[JsValue] === Json.parse(
              """
              |[{
              |  "sequenceNr" : 0,
              |  "event" : {
              |    "remoteAddress" : "wAACAA==",
              |    "publicKey" : "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA1V7/LB8zmkptcEH1/b6hUjdvzRW47/ZadR189o1EizOulLXRpWTgarXQ9bMcP8/exkHO/TKPPmRj/n206ydApG2lsq2px7lhOJnheQzGf8A/X/IpDOGL0sP4e23EV8MaxocsbuzGWrqM6z478L/+Qk1ntG7DmOTReSfWpgQ70IzVTgnq9fUqP+qu6/3qSmT4JMFE0YBYfCCtiMYrGN2LoQ0sq9peapuguxtCOIoOXAlo4UsnbN6KZrr1ggEIfOwUfSgoOpZ6andxwPh9M7f3AdD5RLneounQBz7bX5TKvICZz0PL3SkBxpBX0qENZtxnnPpgy15AeSTVVTDHUFhu2QIDAQAB",
              |    "timestamp" : "1514156286183",
              |    "event" : {
              |      "zoneCreatedEvent" : {
              |        "zone" : {
              |          "id" : "32824da3-094f-45f0-9b35-23b7827547c6",
              |          "equityAccountId" : "0",
              |          "members" : [ {
              |              "id" : "0",
              |              "ownerPublicKeys" : [ "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA1V7/LB8zmkptcEH1/b6hUjdvzRW47/ZadR189o1EizOulLXRpWTgarXQ9bMcP8/exkHO/TKPPmRj/n206ydApG2lsq2px7lhOJnheQzGf8A/X/IpDOGL0sP4e23EV8MaxocsbuzGWrqM6z478L/+Qk1ntG7DmOTReSfWpgQ70IzVTgnq9fUqP+qu6/3qSmT4JMFE0YBYfCCtiMYrGN2LoQ0sq9peapuguxtCOIoOXAlo4UsnbN6KZrr1ggEIfOwUfSgoOpZ6andxwPh9M7f3AdD5RLneounQBz7bX5TKvICZz0PL3SkBxpBX0qENZtxnnPpgy15AeSTVVTDHUFhu2QIDAQAB" ],
              |              "name" : "Dave"
              |          } ],
              |          "accounts" : [ {
              |              "id" : "0",
              |              "ownerMemberIds" : [ "0" ]
              |          } ],
              |          "created" : "1514156286183",
              |          "expires" : "1514761086183",
              |          "name" : "Dave's Game"
              |        }
              |      }
              |    }
              |  }
              |},
              |{
              |  "sequenceNr" : 1,
              |  "event" : {
              |    "remoteAddress" : "wAACAA==",
              |    "publicKey" : "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA1V7/LB8zmkptcEH1/b6hUjdvzRW47/ZadR189o1EizOulLXRpWTgarXQ9bMcP8/exkHO/TKPPmRj/n206ydApG2lsq2px7lhOJnheQzGf8A/X/IpDOGL0sP4e23EV8MaxocsbuzGWrqM6z478L/+Qk1ntG7DmOTReSfWpgQ70IzVTgnq9fUqP+qu6/3qSmT4JMFE0YBYfCCtiMYrGN2LoQ0sq9peapuguxtCOIoOXAlo4UsnbN6KZrr1ggEIfOwUfSgoOpZ6andxwPh9M7f3AdD5RLneounQBz7bX5TKvICZz0PL3SkBxpBX0qENZtxnnPpgy15AeSTVVTDHUFhu2QIDAQAB",
              |    "timestamp" : "1514156287183",
              |    "event" : {
              |      "memberCreatedEvent" : {
              |        "member" : {
              |          "id" : "1",
              |          "ownerPublicKeys" : [ "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA1V7/LB8zmkptcEH1/b6hUjdvzRW47/ZadR189o1EizOulLXRpWTgarXQ9bMcP8/exkHO/TKPPmRj/n206ydApG2lsq2px7lhOJnheQzGf8A/X/IpDOGL0sP4e23EV8MaxocsbuzGWrqM6z478L/+Qk1ntG7DmOTReSfWpgQ70IzVTgnq9fUqP+qu6/3qSmT4JMFE0YBYfCCtiMYrGN2LoQ0sq9peapuguxtCOIoOXAlo4UsnbN6KZrr1ggEIfOwUfSgoOpZ6andxwPh9M7f3AdD5RLneounQBz7bX5TKvICZz0PL3SkBxpBX0qENZtxnnPpgy15AeSTVVTDHUFhu2QIDAQAB" ],
              |          "name" : "Jenny"
              |        }
              |      }
              |    }
              |  }
              |},
              |{
              |  "sequenceNr" : 2,
              |  "event" : {
              |    "remoteAddress" : "wAACAA==",
              |    "publicKey" : "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA1V7/LB8zmkptcEH1/b6hUjdvzRW47/ZadR189o1EizOulLXRpWTgarXQ9bMcP8/exkHO/TKPPmRj/n206ydApG2lsq2px7lhOJnheQzGf8A/X/IpDOGL0sP4e23EV8MaxocsbuzGWrqM6z478L/+Qk1ntG7DmOTReSfWpgQ70IzVTgnq9fUqP+qu6/3qSmT4JMFE0YBYfCCtiMYrGN2LoQ0sq9peapuguxtCOIoOXAlo4UsnbN6KZrr1ggEIfOwUfSgoOpZ6andxwPh9M7f3AdD5RLneounQBz7bX5TKvICZz0PL3SkBxpBX0qENZtxnnPpgy15AeSTVVTDHUFhu2QIDAQAB",
              |    "timestamp" : "1514156288183",
              |    "event" : {
              |      "accountCreatedEvent" : {
              |        "account" : {
              |          "id" : "1",
              |          "name" : "Jenny's Account",
              |          "ownerMemberIds" : [ "1" ]
              |        }
              |      }
              |    }
              |  }
              |},
              |{
              |  "sequenceNr" : 3,
              |  "event" : {
              |    "remoteAddress" : "wAACAA==",
              |    "publicKey" : "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA1V7/LB8zmkptcEH1/b6hUjdvzRW47/ZadR189o1EizOulLXRpWTgarXQ9bMcP8/exkHO/TKPPmRj/n206ydApG2lsq2px7lhOJnheQzGf8A/X/IpDOGL0sP4e23EV8MaxocsbuzGWrqM6z478L/+Qk1ntG7DmOTReSfWpgQ70IzVTgnq9fUqP+qu6/3qSmT4JMFE0YBYfCCtiMYrGN2LoQ0sq9peapuguxtCOIoOXAlo4UsnbN6KZrr1ggEIfOwUfSgoOpZ6andxwPh9M7f3AdD5RLneounQBz7bX5TKvICZz0PL3SkBxpBX0qENZtxnnPpgy15AeSTVVTDHUFhu2QIDAQAB",
              |    "timestamp" : "1514156289183",
              |    "event" : {
              |      "transactionAddedEvent" : {
              |        "transaction" : {
              |          "id" : "0",
              |          "from" : "0",
              |          "to" : "1",
              |          "value" : "5000",
              |          "creator" : "0",
              |          "created" : "1514156289183",
              |          "description" : "Jenny's Lottery Win"
              |        }
              |      }
              |    }
              |  }
              |}]
            """.stripMargin
            ))
        }
      }
      "for zones" in {
        val getRequest =
          RequestBuilding.Get(s"/diagnostics/zones/${UUID.randomUUID()}")
        getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
          assert(status === StatusCodes.OK)
          assert(contentType === ContentType(`application/json`))
          assert(
            entityAs[JsValue] === Json.parse(
              """
              |{
              |  "zone" : {
              |    "id" : "32824da3-094f-45f0-9b35-23b7827547c6",
              |    "equityAccountId" : "0",
              |    "members" : [ {
              |      "id" : "0",
              |      "ownerPublicKeys": [ "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA1V7/LB8zmkptcEH1/b6hUjdvzRW47/ZadR189o1EizOulLXRpWTgarXQ9bMcP8/exkHO/TKPPmRj/n206ydApG2lsq2px7lhOJnheQzGf8A/X/IpDOGL0sP4e23EV8MaxocsbuzGWrqM6z478L/+Qk1ntG7DmOTReSfWpgQ70IzVTgnq9fUqP+qu6/3qSmT4JMFE0YBYfCCtiMYrGN2LoQ0sq9peapuguxtCOIoOXAlo4UsnbN6KZrr1ggEIfOwUfSgoOpZ6andxwPh9M7f3AdD5RLneounQBz7bX5TKvICZz0PL3SkBxpBX0qENZtxnnPpgy15AeSTVVTDHUFhu2QIDAQAB" ],
              |      "name":"Dave"
              |    } ],
              |    "accounts" : [ {
              |      "id" :"0",
              |      "ownerMemberIds" : [ "0" ]
              |    } ],
              |    "created" : "1514156286183",
              |    "expires" : "1514761086183",
              |    "name" : "Dave's Game"
              |  }
              |}
            """.stripMargin
            ))
        }
      }
    }
    "accepts WebSocket connections" in {
      val wsProbe = WSProbe()
      WS("/ws", wsProbe.flow)
        .addHeader(
          `Remote-Address`(RemoteAddress(InetAddress.getLoopbackAddress))
        ) ~> httpRoutes(enableClientRelay = true) ~> check {
        assert(isWebSocketUpgrade === true)
        val message = "Hello"
        wsProbe.sendMessage(message)
        wsProbe.expectMessage(message)
        wsProbe.sendCompletion()
        wsProbe.expectCompletion()
      }
    }
    "provides status information" in {
      val getRequest = RequestBuilding.Get("/status")
      getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
        assert(status === StatusCodes.OK)
        assert(contentType === ContentType(`application/json`))
        assert(
          entityAs[JsValue] === Json.parse(
            """
              |{
              |  "activeClients" : {
              |    "count" : 1,
              |    "publicKeyFingerprints" : [ "0280473ff02de92c971948a1253a3318507f870d20f314e844520058888512be" ]
              |  },
              |  "activeZones" : {
              |    "count" : 1,
              |    "zones" : [ {
              |      "zoneIdFingerprint" : "b697e3a3a1eceb99d9e0b3e932e47596e77dfab19697d6fe15b3b0db75e96f12",
              |      "metadata" : null,
              |      "members" : 2,
              |      "accounts" : 2,
              |      "transactions" : 1,
              |      "clientConnections" : {
              |        "count" : 1,
              |        "publicKeyFingerprints" : [ "0280473ff02de92c971948a1253a3318507f870d20f314e844520058888512be" ]
              |      }
              |    } ]
              |  },
              |  "totals" : {
              |    "zones" : 1,
              |    "publicKeys" : 1,
              |    "members" : 2,
              |    "accounts" : 2,
              |    "transactions" : 1
              |  }
              |}
            """.stripMargin
          ))
      }
    }
    "provides analytics information" - {
      "for zones" in {
        val getRequest =
          RequestBuilding.Get(s"/analytics/zones/${UUID.randomUUID()}")
        getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
          assert(status === StatusCodes.OK)
          assert(contentType === ContentType(`application/json`))
          assert(
            entityAs[JsValue] === Json.parse(
              """
                |{
                |  "id" : "32824da3-094f-45f0-9b35-23b7827547c6",
                |  "equityAccountId" : "0",
                |  "members" : [ {
                |    "id" : "0",
                |    "ownerPublicKeys": [ "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA1V7/LB8zmkptcEH1/b6hUjdvzRW47/ZadR189o1EizOulLXRpWTgarXQ9bMcP8/exkHO/TKPPmRj/n206ydApG2lsq2px7lhOJnheQzGf8A/X/IpDOGL0sP4e23EV8MaxocsbuzGWrqM6z478L/+Qk1ntG7DmOTReSfWpgQ70IzVTgnq9fUqP+qu6/3qSmT4JMFE0YBYfCCtiMYrGN2LoQ0sq9peapuguxtCOIoOXAlo4UsnbN6KZrr1ggEIfOwUfSgoOpZ6andxwPh9M7f3AdD5RLneounQBz7bX5TKvICZz0PL3SkBxpBX0qENZtxnnPpgy15AeSTVVTDHUFhu2QIDAQAB" ],
                |    "name":"Dave"
                |  } ],
                |  "accounts" : [ {
                |    "id" :"0",
                |    "ownerMemberIds" : [ "0" ]
                |  } ],
                |  "created" : "1514156286183",
                |  "expires" : "1514761086183",
                |  "name" : "Dave's Game"
                |}
              """.stripMargin
            ))
        }
      }
      "for balances" in {
        val getRequest =
          RequestBuilding.Get(s"/analytics/zones/${UUID.randomUUID()}/balances")
        getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
          assert(status === StatusCodes.OK)
          assert(contentType === ContentType(`application/json`))
          assert(
            entityAs[JsValue] === Json.parse(
              """
                  |{
                  |  "0" : -5000,
                  |  "1" : 5000
                  |}
                """.stripMargin
            ))
        }
      }
    }
  }

  override protected[this] def events(
      persistenceId: String,
      fromSequenceNr: Long,
      toSequenceNr: Long): Source[EventEnvelope, NotUsed] =
    Source(
      Seq(
        ZoneCreatedEvent(zone),
        MemberCreatedEvent(
          Member(
            id = MemberId("1"),
            ownerPublicKeys = Set(publicKey),
            name = Some("Jenny"),
            metadata = None
          )),
        AccountCreatedEvent(
          Account(
            id = AccountId("1"),
            ownerMemberIds = Set(MemberId("1")),
            name = Some("Jenny's Account"),
            metadata = None
          )),
        TransactionAddedEvent(Transaction(
          id = TransactionId("0"),
          from = AccountId("0"),
          to = AccountId("1"),
          value = BigDecimal(5000),
          creator = MemberId("0"),
          created = created + 3000,
          description = Some("Jenny's Lottery Win"),
          metadata = None
        ))
      ).zipWithIndex.map {
        case (event, index) =>
          val zoneEventEnvelope = ZoneEventEnvelope(
            remoteAddress = Some(InetAddress.getByName("192.0.2.0")),
            publicKey = Some(publicKey),
            timestamp = Instant.ofEpochMilli(created + (index * 1000)),
            zoneEvent = event
          )
          EventEnvelope(
            sequenceNr = index.toLong,
            event = ProtoBinding[ZoneEventEnvelope,
                                 proto.persistence.zone.ZoneEventEnvelope,
                                 ActorRefResolver]
              .asProto(zoneEventEnvelope)(ActorRefResolver(system.toTyped))
          )
      })

  override protected[this] def zoneState(
      zoneId: ZoneId): Future[proto.persistence.zone.ZoneState] =
    Future.successful(
      proto.persistence.zone
        .ZoneState(
          zone =
            Some(ProtoBinding[Zone, proto.model.Zone, Any].asProto(zone)(())),
          balances = Map.empty,
          connectedClients = Map.empty
        )
    )

  override protected[this] def webSocketApi(
      remoteAddress: InetAddress): Flow[Message, Message, NotUsed] =
    Flow[Message]

  override protected[this] def getActiveClientSummaries
    : Future[Set[ActiveClientSummary]] =
    Future.successful(Set(ActiveClientSummary(publicKey)))

  override protected[this] def getActiveZoneSummaries
    : Future[Set[ActiveZoneSummary]] =
    Future.successful(
      Set(
        ActiveZoneSummary(
          zoneId,
          members = 2,
          accounts = 2,
          transactions = 1,
          metadata = None,
          clientConnections = Set(publicKey)
        )
      )
    )

  override protected[this] def getZone(zoneId: ZoneId): Future[Option[Zone]] =
    Future.successful(Some(zone))

  override protected[this] def getBalances(
      zoneId: ZoneId): Future[Map[AccountId, BigDecimal]] =
    Future.successful(
      Map(
        equityAccountId -> BigDecimal(-5000),
        AccountId("1") -> BigDecimal(5000)
      )
    )

  override protected[this] def getZoneCount: Future[Long] =
    Future.successful(1)

  override protected[this] def getPublicKeyCount: Future[Long] =
    Future.successful(1)

  override protected[this] def getMemberCount: Future[Long] =
    Future.successful(2)

  override protected[this] def getAccountCount: Future[Long] =
    Future.successful(2)

  override protected[this] def getTransactionCount: Future[Long] =
    Future.successful(1)

}
