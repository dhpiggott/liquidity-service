package com.dhpcs.liquidity.service

import java.net.InetAddress
import java.security.KeyPairGenerator
import java.time.Instant

import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers
import akka.stream.scaladsl.Source
import cats.syntax.validated._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.service.HttpControllerSpec._
import com.dhpcs.liquidity.ws.protocol._
import org.json4s._
import org.scalatest.FreeSpec
import zio.DefaultRuntime

import scala.collection.immutable.Seq
import scala.concurrent.Future

class HttpControllerSpec extends FreeSpec with ScalatestRouteTest {

  private[this] implicit val serialization: Serialization = native.Serialization
  private[this] implicit val formats: Formats = DefaultFormats

  "HttpController" - {
    "provides ready information" in {
      val getRequest = RequestBuilding.Get("/ready")
      getRequest ~> httpController.restRoute ~> check {
        assert(status === StatusCodes.OK)
        import PredefinedFromEntityUnmarshallers.stringUnmarshaller
        assert(entityAs[String] === "OK")
      }
    }
    "provides version information" in {
      val getRequest = RequestBuilding.Get("/version")
      getRequest ~> httpController.restRoute ~> check {
        assert(status === StatusCodes.OK)
        import de.heikoseeberger.akkahttpjson4s.Json4sSupport._
        assert(
          entityAs[JObject] === JObject(
            "version" -> JString(BuildInfo.version),
            "builtAtMillis" -> JString(BuildInfo.builtAtMillis.toString),
            "builtAtString" -> JString(BuildInfo.builtAtString)
          )
        )
      }
    }
  }

  private[this] val httpController = new HttpController(
    ready = _.complete("OK"),
    execZoneCommand = (remoteAddress, publicKey, zoneId, zoneCommand) =>
      Future.successful(
        if (remoteAddress == HttpControllerSpec.remoteAddress &&
            publicKey == HttpControllerSpec.publicKey)
          if (zoneCommand == CreateZoneCommand(
                equityOwnerPublicKey = publicKey,
                equityOwnerName = zone
                  .members(
                    zone.accounts(zone.equityAccountId).ownerMemberIds.head
                  )
                  .name,
                equityOwnerMetadata = None,
                equityAccountName = None,
                equityAccountMetadata = None,
                name = zone.name,
                metadata = None
              ))
            CreateZoneResponse(zone.valid)
          else if (zoneId == zone.id &&
                   zoneCommand == ChangeZoneNameCommand(
                     zoneId = zone.id,
                     name = None
                   ))
            ChangeZoneNameResponse(().valid)
          else fail()
        else fail()
      ),
    zoneNotificationSource = (_, _, zoneId) =>
      if (zoneId != zone.id) Source.empty
      else Source(zoneNotifications),
    runtime = new DefaultRuntime {}
  )

}

object HttpControllerSpec {

  private val remoteAddress = InetAddress.getByName("192.0.2.0")
  private val publicKey = {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(2048)
    PublicKey(keyPairGenerator.generateKeyPair.getPublic.getEncoded)
  }
  private val zone = {
    val created = Instant.ofEpochMilli(1514156286183L)
    val equityAccountId = AccountId(0.toString)
    val equityAccountOwnerId = MemberId(0.toString)
    Zone(
      id = ZoneId("32824da3-094f-45f0-9b35-23b7827547c6"),
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
  }

  private val zoneNotifications = Seq(
    ZoneStateNotification(
      Some(zone),
      connectedClients = Map("HttpControllerSpec" -> publicKey)
    ),
    MemberCreatedNotification(
      Member(
        id = MemberId("1"),
        ownerPublicKeys = Set(publicKey),
        name = Some("Jenny"),
        metadata = None
      )
    ),
    AccountCreatedNotification(
      Account(
        id = AccountId("1"),
        ownerMemberIds = Set(MemberId("1")),
        name = Some("Jenny's Account"),
        metadata = None
      )
    ),
    TransactionAddedNotification(
      Transaction(
        id = TransactionId("0"),
        from = AccountId("0"),
        to = AccountId("1"),
        value = BigDecimal("5000000000000000000000"),
        creator = MemberId("0"),
        created = zone.created.plusMillis(3000),
        description = Some("Jenny's Lottery Win"),
        metadata = None
      )
    )
  )

}
