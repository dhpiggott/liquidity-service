package com.dhpcs.liquidity.server

import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.time.Instant
import java.util.UUID

import akka.NotUsed
import akka.actor.typed.ActorRefResolver
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.marshalling.PredefinedToEntityMarshallers
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.ws.{Message => WsMessage}
import akka.http.scaladsl.server.StandardRoute
import akka.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers
import akka.stream.scaladsl.{Flow, Source}
import akka.testkit.TestProbe
import akka.util.ByteString
import cats.syntax.validated._
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
import com.dhpcs.liquidity.server.SqlAnalyticsStore.ClientSessionsStore._
import com.dhpcs.liquidity.ws.protocol.ProtoBindings._
import com.dhpcs.liquidity.ws.protocol._
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport._
import org.scalatest.FreeSpec
import pdi.jwt.{JwtAlgorithm, JwtJson}
import play.api.libs.json.{JsObject, JsString, JsValue, Json}

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration._

class HttpControllerSpec
    extends FreeSpec
    with HttpController
    with ScalatestRouteTest {

  "HttpController" - {
    "rejects access" - {
      "when no bearer token is presented" in {
        val getRequest = RequestBuilding
          .Get("/akka-management")
        getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
          assert(status === StatusCodes.Unauthorized)
          assert(
            header[`WWW-Authenticate`].contains(
              `WWW-Authenticate`(
                HttpChallenges
                  .oAuth2(realm = null)
                  .copy(
                    params = Map(
                      "error" ->
                        "Bearer token authorization must be presented."
                    )
                  )
              )
            )
          )
          import PredefinedFromEntityUnmarshallers.stringUnmarshaller
          assert(entityAs[String] === StatusCodes.Unauthorized.defaultMessage)
        }
      }
      "when the token is not a JWT" in {
        val getRequest =
          RequestBuilding
            .Get("/akka-management")
            .withHeaders(Authorization(OAuth2BearerToken("")))
        getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
          assert(status === StatusCodes.Unauthorized)
          assert(
            header[`WWW-Authenticate`].contains(
              `WWW-Authenticate`(
                HttpChallenges
                  .oAuth2(realm = null)
                  .copy(
                    params = Map("error" -> "Token must be a JWT.")
                  )
              )
            )
          )
          import PredefinedFromEntityUnmarshallers.stringUnmarshaller
          assert(entityAs[String] === StatusCodes.Unauthorized.defaultMessage)
        }
      }
      "when the token claims do not contain a subject" in {
        val getRequest =
          RequestBuilding
            .Get("/akka-management")
            .withHeaders(
              Authorization(
                OAuth2BearerToken(
                  JwtJson.encode(
                    Json.obj(),
                    rsaPrivateKey,
                    JwtAlgorithm.RS256
                  )
                )
              )
            )
        getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
          assert(status === StatusCodes.Unauthorized)
          assert(
            header[`WWW-Authenticate`].contains(
              `WWW-Authenticate`(
                HttpChallenges
                  .oAuth2(realm = null)
                  .copy(
                    params =
                      Map("error" -> "Token claims must contain a subject.")
                  )
              )
            )
          )
          import PredefinedFromEntityUnmarshallers.stringUnmarshaller
          assert(entityAs[String] === StatusCodes.Unauthorized.defaultMessage)
        }
      }
      "when the token subject is not an RSA public key" in {
        val getRequest =
          RequestBuilding
            .Get("/akka-management")
            .withHeaders(
              Authorization(
                OAuth2BearerToken(
                  JwtJson.encode(
                    Json.obj("sub" -> ""),
                    rsaPrivateKey,
                    JwtAlgorithm.RS256
                  )
                )
              )
            )
        getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
          assert(status === StatusCodes.Unauthorized)
          assert(
            header[`WWW-Authenticate`].contains(
              `WWW-Authenticate`(
                HttpChallenges
                  .oAuth2(realm = null)
                  .copy(
                    params = Map(
                      "error" ->
                        "Token subject must be an RSA public key."
                    )
                  )
              )
            )
          )
          import PredefinedFromEntityUnmarshallers.stringUnmarshaller
          assert(entityAs[String] === StatusCodes.Unauthorized.defaultMessage)
        }
      }
      "when the token is not signed by the subject's private key" in {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        val otherRsaPrivateKey = keyPairGenerator.generateKeyPair.getPrivate
        val getRequest =
          RequestBuilding
            .Get("/akka-management")
            .withHeaders(
              Authorization(
                OAuth2BearerToken(
                  JwtJson.encode(
                    Json.obj(
                      "sub" -> okio.ByteString
                        .of(rsaPublicKey.getEncoded: _*)
                        .base64()
                    ),
                    otherRsaPrivateKey,
                    JwtAlgorithm.RS256
                  )
                )
              )
            )
        getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
          assert(status === StatusCodes.Unauthorized)
          assert(
            header[`WWW-Authenticate`].contains(
              `WWW-Authenticate`(
                HttpChallenges
                  .oAuth2(realm = null)
                  .copy(
                    params = Map(
                      "error" ->
                        ("Token must be signed by subject's private key and " +
                          "used between nbf and iat claims.")
                    )
                  )
              )
            )
          )
          import PredefinedFromEntityUnmarshallers.stringUnmarshaller
          assert(entityAs[String] === StatusCodes.Unauthorized.defaultMessage)
        }
      }
      "when the subject is not an administrator" in {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        val keyPair = keyPairGenerator.generateKeyPair
        val otherRsaPrivateKey = keyPair.getPrivate
        val otherRsaPublicKey = keyPair.getPublic
        val getRequest =
          RequestBuilding
            .Get("/akka-management")
            .withHeaders(
              Authorization(
                OAuth2BearerToken(
                  JwtJson.encode(
                    Json.obj(
                      "sub" -> okio.ByteString
                        .of(otherRsaPublicKey.getEncoded: _*)
                        .base64()
                    ),
                    otherRsaPrivateKey,
                    JwtAlgorithm.RS256
                  )
                )
              )
            )
        getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
          assert(status === StatusCodes.Forbidden)
          import PredefinedFromEntityUnmarshallers.stringUnmarshaller
          assert(entityAs[String] === StatusCodes.Forbidden.defaultMessage)
        }
      }
    }
    "proxies /akka-management to akkaManagement" in {
      val getRequest =
        RequestBuilding
          .Get("/akka-management")
          .withHeaders(Authorization(OAuth2BearerToken(selfSignedJwt)))
      getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
        assert(status === StatusCodes.OK)
        import PredefinedFromEntityUnmarshallers.stringUnmarshaller
        assert(entityAs[String] === "akka-management")
      }
    }
    "provides diagnostic information" - {
      "for events" in {
        val getRequest = RequestBuilding
          .Get(
            Uri.Empty.withPath(
              Uri.Path("/diagnostics/events") / s"zone-${zone.id.value}"
            )
          )
          .withHeaders(Authorization(OAuth2BearerToken(selfSignedJwt)))
        getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
          assert(status === StatusCodes.OK)
          assert(entityAs[JsValue] === Json.parse(s"""
               |[{
               |  "sequenceNr" : 0,
               |  "event" : {
               |    "remoteAddress" : "wAACAA==",
               |    "publicKey" : "${publicKey.value.base64()}",
               |    "timestamp" : "1514156286183",
               |    "event" : {
               |      "zoneCreatedEvent" : {
               |        "zone" : {
               |          "id" : "32824da3-094f-45f0-9b35-23b7827547c6",
               |          "equityAccountId" : "0",
               |          "members" : [ {
               |              "id" : "0",
               |              "ownerPublicKeys" : [ "${publicKey.value
                                                       .base64()}" ],
               |              "ownerPublicKeys" : [ "${publicKey.value
                                                       .base64()}" ],
               |              "name" : "Dave"
               |          } ],
               |          "accounts" : [ {
               |              "id" : "0",
               |              "ownerMemberIds" : [ "0" ]
               |          } ],
               |          "created" : "1514156286183",
               |          "expires" : "1516748286183",
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
               |    "publicKey" : "${publicKey.value.base64()}",
               |    "timestamp" : "1514156287183",
               |    "event" : {
               |      "memberCreatedEvent" : {
               |        "member" : {
               |          "id" : "1",
               |          "ownerPublicKeys" : [ "${publicKey.value.base64()}" ],
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
               |    "publicKey" : "${publicKey.value.base64()}",
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
               |    "publicKey" : "${publicKey.value.base64()}",
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
             """.stripMargin))
        }
      }
      "for zones" in {
        val getRequest = RequestBuilding
          .Get(
            Uri.Empty.withPath(
              Uri.Path("/diagnostics/zone") / zone.id.value
            )
          )
          .withHeaders(Authorization(OAuth2BearerToken(selfSignedJwt)))
        getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
          assert(status === StatusCodes.OK)
          assert(entityAs[JsValue] === Json.parse(s"""
               |{
               |  "zone" : {
               |    "id" : "32824da3-094f-45f0-9b35-23b7827547c6",
               |    "equityAccountId" : "0",
               |    "members" : [ {
               |      "id" : "0",
               |      "ownerPublicKeys": [ "${publicKey.value.base64()}" ],
               |      "name":"Dave"
               |    } ],
               |    "accounts" : [ {
               |      "id" :"0",
               |      "ownerMemberIds" : [ "0" ]
               |    } ],
               |    "created" : "1514156286183",
               |    "expires" : "1516748286183",
               |    "name" : "Dave's Game"
               |  }
               |}
             """.stripMargin))
        }
      }
    }
    "provides analytics information" - {
      "for zones" - {
        "with status 404 when the zone does not exist" in {
          val getRequest = RequestBuilding
            .Get(
              Uri.Empty.withPath(
                Uri.Path("/analytics/zone") / UUID.randomUUID().toString
              )
            )
            .withHeaders(Authorization(OAuth2BearerToken(selfSignedJwt)))
          getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
            assert(status === StatusCodes.NotFound)
          }
        }
        "with status 200 when the zone exists" in {
          val getRequest =
            RequestBuilding
              .Get(
                Uri.Empty.withPath(
                  Uri.Path("/analytics/zone") / zone.id.value
                )
              )
              .withHeaders(Authorization(OAuth2BearerToken(selfSignedJwt)))
          getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
            assert(status === StatusCodes.OK)
            assert(
              entityAs[JsValue] === Json.parse(
                s"""
                 |{
                 |  "id" : "32824da3-094f-45f0-9b35-23b7827547c6",
                 |  "equityAccountId" : "0",
                 |  "members" : [ {
                 |    "id" : "0",
                 |    "ownerPublicKeys": [ "${publicKey.value.base64()}" ],
                 |    "name":"Dave"
                 |  } ],
                 |  "accounts" : [ {
                 |    "id" :"0",
                 |    "ownerMemberIds" : [ "0" ]
                 |  } ],
                 |  "created" : "1514156286183",
                 |  "expires" : "1516748286183",
                 |  "name" : "Dave's Game"
                 |}
               """.stripMargin
              ))
          }
        }
      }
      "for balances" in {
        val getRequest =
          RequestBuilding
            .Get(
              Uri.Empty.withPath(
                Uri.Path("/analytics/zone") / zone.id.value / "balances"
              )
            )
            .withHeaders(Authorization(OAuth2BearerToken(selfSignedJwt)))
        getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
          assert(status === StatusCodes.OK)
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
      "for client-sessions" in {
        val getRequest =
          RequestBuilding
            .Get(
              Uri.Empty.withPath(
                Uri.Path("/analytics/zone") / zone.id.value / "client-sessions"
              )
            )
            .withHeaders(Authorization(OAuth2BearerToken(selfSignedJwt)))
        getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
          assert(status === StatusCodes.OK)
          assert(entityAs[JsValue] === Json.parse(s"""
               |{
               |  "1" : {
               |    "id" : "1",
               |    "remoteAddress":"192.0.2.0",
               |    "actorRef" : "$actorRef",
               |    "publicKeyFingerprint" : "${publicKey.fingerprint}",
               |    "joined" : "$joined",
               |    "quit" : null
               |  }
               |}
             """.stripMargin))
        }
      }
    }
    "provides version information" in {
      val getRequest = RequestBuilding.Get("/version")
      getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
        assert(status === StatusCodes.OK)
        val keys = entityAs[JsObject].value.keySet
        assert(keys.contains("version"))
        assert(keys.contains("builtAtString"))
        assert(keys.contains("builtAtMillis"))
      }
    }
    "provides status information" - {
      "tersely" in {
        val getRequest = RequestBuilding.Get("/status/terse")
        getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
          assert(status === StatusCodes.OK)
          assert(entityAs[JsValue] === JsString("OK"))
        }
      }
      "verbosely" in {
        val getRequest = RequestBuilding.Get("/status/verbose")
        getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
          assert(status === StatusCodes.OK)
          assert(entityAs[JsValue] === Json.parse(s"""
             |{
             |  "activeClients" : {
             |    "count" : 1,
             |    "clients" : [ {
             |      "hostAddressFingerprint" : "14853799b55e545f862f2fc26bca37ab6adbb7a3696db3ee733c8c78714de3c4",
             |      "count" : 1,
             |      "clientsAtHostAddress" : [ {
             |        "publicKeyFingerprint" : "${publicKey.fingerprint}",
             |        "count" : 1,
             |        "clientsWithPublicKey" : {
             |          "count" : 1,
             |          "connectionIds" : [
             |            "test-connection-id"
             |          ]
             |        }
             |      } ]
             |    } ]
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
             |        "publicKeyFingerprints" : [ "${publicKey.fingerprint}" ]
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
           """.stripMargin))
        }
      }
    }
    "accepts CreateZoneCommands" - {
      "with JSON encoding" in {
        val putRequest = RequestBuilding
          .Put("/zone")
          .withHeaders(
            `Remote-Address`(RemoteAddress(remoteAddress)),
            Authorization(OAuth2BearerToken(selfSignedJwt))
          )
          .withEntity(
            ContentTypes.`application/json`,
            s"""
            |{
            |  "equityOwnerPublicKey": "${publicKey.value.base64()}",
            |  "equityOwnerName": "Dave",
            |  "name": "Dave's Game"
            |}
          """.stripMargin
          )
        putRequest ~> httpRoutes(enableClientRelay = true) ~> check {
          assert(status === StatusCodes.OK)
          assert(entityAs[JsValue] === Json.parse(s"""
             |{
             |  "createZoneResponse": {
             |    "success": {
             |      "zone": {
             |        "id" : "32824da3-094f-45f0-9b35-23b7827547c6",
             |        "equityAccountId" : "0",
             |        "members" : [ {
             |          "id" : "0",
             |          "ownerPublicKeys": [ "${publicKey.value.base64()}" ],
             |          "name":"Dave"
             |        } ],
             |        "accounts" : [ {
             |        "id" :"0",
             |          "ownerMemberIds" : [ "0" ]
             |        } ],
             |        "created" : "1514156286183",
             |        "expires" : "1516748286183",
             |        "name" : "Dave's Game"
             |      }
             |    }
             |  }
             |}
           """.stripMargin))
        }
      }
      "with Protobuf encoding" in {
        val putRequest = RequestBuilding
          .Put("/zone")
          .withHeaders(
            `Remote-Address`(RemoteAddress(remoteAddress)),
            Authorization(OAuth2BearerToken(selfSignedJwt)),
            Accept(
              MediaRange(
                MediaType.customBinary(mainType = "application",
                                       subType = "x-protobuf",
                                       comp = MediaType.NotCompressible)
              )
            )
          )
          .withEntity(
            ContentType(
              MediaType.customBinary(mainType = "application",
                                     subType = "x-protobuf",
                                     comp = MediaType.NotCompressible)
            ),
            ProtoBinding[CreateZoneCommand,
                         proto.ws.protocol.ZoneCommand.CreateZoneCommand,
                         Any]
              .asProto(
                CreateZoneCommand(
                  equityOwnerPublicKey = publicKey,
                  equityOwnerName = zone
                    .members(
                      zone.accounts(zone.equityAccountId).ownerMemberIds.head)
                    .name,
                  equityOwnerMetadata = None,
                  equityAccountName = None,
                  equityAccountMetadata = None,
                  name = zone.name,
                  metadata = None
                )
              )(())
              .toByteArray
          )
        putRequest ~> httpRoutes(enableClientRelay = true) ~> check {
          assert(status === StatusCodes.OK)
          import PredefinedFromEntityUnmarshallers.byteArrayUnmarshaller
          assert(
            entityAs[Array[Byte]] ===
              ProtoBinding[ZoneResponse, proto.ws.protocol.ZoneResponse, Any]
                .asProto(CreateZoneResponse(zone.valid))(())
                .toByteArray
          )
        }
      }
    }
    "accepts ZoneCommands" - {
      "with JSON encoding" in {
        val putRequest = RequestBuilding
          .Put(
            Uri.Empty.withPath(
              Uri.Path("/zone") / zone.id.value
            )
          )
          .withHeaders(
            `Remote-Address`(RemoteAddress(remoteAddress)),
            Authorization(OAuth2BearerToken(selfSignedJwt))
          )
          .withEntity(
            ContentTypes.`application/json`,
            s"""
             |{
             |  "changeZoneNameCommand": {
             |  }
             |}
          """.stripMargin
          )
        putRequest ~> httpRoutes(enableClientRelay = true) ~> check {
          assert(status === StatusCodes.OK)
          assert(entityAs[JsValue] === Json.parse(s"""
             |{
             |  "changeZoneNameResponse": {
             |  }
             |}
           """.stripMargin))
        }
      }
      "with Protobuf encoding" in {
        val putRequest = RequestBuilding
          .Put(
            Uri.Empty.withPath(
              Uri.Path("/zone") / zone.id.value
            )
          )
          .withHeaders(
            `Remote-Address`(RemoteAddress(remoteAddress)),
            Authorization(OAuth2BearerToken(selfSignedJwt)),
            Accept(
              MediaRange(
                MediaType.customBinary(mainType = "application",
                                       subType = "x-protobuf",
                                       comp = MediaType.NotCompressible)
              )
            )
          )
          .withEntity(
            ContentType(
              MediaType.customBinary(mainType = "application",
                                     subType = "x-protobuf",
                                     comp = MediaType.NotCompressible)
            ),
            ProtoBinding[ZoneCommand, proto.ws.protocol.ZoneCommand, Any]
              .asProto(
                ChangeZoneNameCommand(name = None)
              )(())
              .toByteArray
          )
        putRequest ~> httpRoutes(enableClientRelay = true) ~> check {
          assert(status === StatusCodes.OK)
          import PredefinedFromEntityUnmarshallers.byteArrayUnmarshaller
          assert(
            entityAs[Array[Byte]] ===
              ProtoBinding[ZoneResponse, proto.ws.protocol.ZoneResponse, Any]
                .asProto(ChangeZoneNameResponse(().valid))(())
                .toByteArray
          )
        }
      }
    }
    "notifies zone notification watchers" - {
      "with JSON encoding" in {
        val getRequest = RequestBuilding
          .Get(
            Uri.Empty.withPath(
              Uri.Path("/zone") / zone.id.value
            )
          )
          .withHeaders(
            `Remote-Address`(RemoteAddress(remoteAddress)),
            Authorization(OAuth2BearerToken(selfSignedJwt))
          )
        getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
          assert(status === StatusCodes.OK)
          assert(entityAs[JsValue] === Json.parse(s"""
               |[{
               |  "zoneStateNotification" : {
               |    "zone" : {
               |      "id" : "32824da3-094f-45f0-9b35-23b7827547c6",
               |      "equityAccountId" : "0",
               |      "members" : [ {
               |          "id" : "0",
               |          "ownerPublicKeys" : [ "${publicKey.value.base64()}" ],
               |          "ownerPublicKeys" : [ "${publicKey.value.base64()}" ],
               |          "name" : "Dave"
               |      } ],
               |      "accounts" : [ {
               |          "id" : "0",
               |          "ownerMemberIds" : [ "0" ]
               |      } ],
               |      "created" : "1514156286183",
               |      "expires" : "1516748286183",
               |      "name" : "Dave's Game"
               |    },
               |    "connectedClients" : {
               |      "HttpControllerSpec" : "${publicKey.value.base64()}"
               |    }
               |  }
               |},
               |{
               |  "memberCreatedNotification" : {
               |    "member" : {
               |      "id" : "1",
               |      "ownerPublicKeys" : [ "${publicKey.value.base64()}" ],
               |      "name" : "Jenny"
               |    }
               |  }
               |},
               |{
               |  "accountCreatedNotification" : {
               |    "account" : {
               |      "id" : "1",
               |      "name" : "Jenny's Account",
               |      "ownerMemberIds" : [ "1" ]
               |    }
               |  }
               |},
               |{
               |  "transactionAddedNotification" : {
               |    "transaction" : {
               |      "id" : "0",
               |      "from" : "0",
               |      "to" : "1",
               |      "value" : "5000",
               |      "creator" : "0",
               |      "created" : "1514156289183",
               |      "description" : "Jenny's Lottery Win"
               |    }
               |  }
               |}]
             """.stripMargin))
        }
      }
      "with Protobuf encoding" in {
        val getRequest = RequestBuilding
          .Get(
            Uri.Empty.withPath(
              Uri.Path("/zone") / zone.id.value
            )
          )
          .withHeaders(
            `Remote-Address`(RemoteAddress(remoteAddress)),
            Authorization(OAuth2BearerToken(selfSignedJwt)),
            Accept(
              MediaRange(
                MediaType.customBinary(mainType = "application",
                                       subType = "x-protobuf",
                                       comp = MediaType.NotCompressible,
                                       params = Map("delimited" -> "true"))
              )
            )
          )
        getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
          assert(status === StatusCodes.OK)
          import PredefinedFromEntityUnmarshallers.byteStringUnmarshaller
          assert(
            proto.ws.protocol.ZoneNotification.streamFromDelimitedInput(
              new ByteArrayInputStream(entityAs[ByteString].toArray)
            ) === zoneNotifications.map(
              zoneNotification =>
                ProtoBinding[ZoneNotification,
                             proto.ws.protocol.ZoneNotification,
                             Any].asProto(zoneNotification)(())
            )
          )
        }
      }
    }
    "accepts WebSocket connections" in {
      val wsProbe = WSProbe()
      WS("/ws", wsProbe.flow)
        .addHeader(
          `Remote-Address`(RemoteAddress(remoteAddress))
        ) ~> httpRoutes(enableClientRelay = true) ~> check {
        assert(isWebSocketUpgrade === true)
        val message = "Hello"
        wsProbe.sendMessage(message)
        wsProbe.expectMessage(message)
        wsProbe.sendCompletion()
        wsProbe.expectCompletion()
      }
    }
  }

  private[this] val actorRef =
    ActorRefResolver(system.toTyped).toSerializationFormat(TestProbe().ref)
  private[this] val joined = Instant.now()
  private[this] val clientSession = ClientSession(
    id = ClientSessionId(1),
    remoteAddress = Some(remoteAddress),
    actorRef = actorRef,
    publicKey = publicKey,
    joined = joined,
    quit = None
  )

  protected[this] def isAdministrator(publicKey: PublicKey): Future[Boolean] =
    Future.successful(
      publicKey.value.toByteArray.sameElements(rsaPublicKey.getEncoded))

  override protected[this] def akkaManagement: StandardRoute =
    requestContext => {
      import PredefinedToEntityMarshallers.StringMarshaller
      requestContext.complete("akka-management")
    }

  override protected[this] def events(
      persistenceId: String,
      fromSequenceNr: Long,
      toSequenceNr: Long): Source[EventEnvelope, NotUsed] =
    if (persistenceId != zone.id.persistenceId)
      Source.empty
    else
      Source(
        Seq(
          ZoneCreatedEvent(
            zone
          ),
          MemberCreatedEvent(
            Member(
              id = MemberId("1"),
              ownerPublicKeys = Set(publicKey),
              name = Some("Jenny"),
              metadata = None
            )
          ),
          AccountCreatedEvent(
            Account(
              id = AccountId("1"),
              ownerMemberIds = Set(MemberId("1")),
              name = Some("Jenny's Account"),
              metadata = None
            )
          ),
          TransactionAddedEvent(
            Transaction(
              id = TransactionId("0"),
              from = AccountId("0"),
              to = AccountId("1"),
              value = BigDecimal(5000),
              creator = MemberId("0"),
              created = zone.created + 3000,
              description = Some("Jenny's Lottery Win"),
              metadata = None
            )
          )
        ).zipWithIndex.map {
          case (event, index) =>
            val zoneEventEnvelope = ZoneEventEnvelope(
              remoteAddress = Some(remoteAddress),
              publicKey = Some(publicKey),
              timestamp = Instant.ofEpochMilli(zone.created + (index * 1000)),
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
            if (zoneId != zone.id)
              None
            else
              Some(
                ProtoBinding[Zone, proto.model.Zone, Any].asProto(
                  zone
                )(())),
          balances = Map.empty,
          connectedClients = Map.empty
        )
    )

  override protected[this] def getZone(zoneId: ZoneId): Future[Option[Zone]] =
    Future.successful(
      if (zoneId != zone.id) None
      else Some(zone)
    )

  override protected[this] def getBalances(
      zoneId: ZoneId): Future[Map[AccountId, BigDecimal]] =
    Future.successful(
      if (zoneId != zone.id) Map.empty
      else balances
    )

  override protected[this] def getClientSessions(
      zoneId: ZoneId): Future[Map[ClientSessionId, ClientSession]] =
    Future.successful(
      if (zoneId != zone.id) Map.empty
      else Map(clientSession.id -> clientSession)
    )

  override protected[this] def checkCluster: Future[Unit] =
    Future.successful(())

  override protected[this] def getActiveClientSummaries
    : Future[Set[ActiveClientSummary]] =
    Future.successful(
      Set(ActiveClientSummary(remoteAddress, publicKey, "test-connection-id")))

  override protected[this] def getActiveZoneSummaries
    : Future[Set[ActiveZoneSummary]] =
    Future.successful(
      Set(
        ActiveZoneSummary(
          zone.id,
          members = 2,
          accounts = 2,
          transactions = 1,
          metadata = None,
          connectedClients = Set(publicKey)
        )
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

  override protected[this] def execCreateZoneCommand(
      remoteAddress: InetAddress,
      publicKey: PublicKey,
      createZoneCommand: CreateZoneCommand): Future[ZoneResponse] =
    Future.successful(
      if (remoteAddress == HttpControllerSpec.remoteAddress &&
          publicKey == HttpControllerSpec.publicKey &&
          createZoneCommand == CreateZoneCommand(
            equityOwnerPublicKey = publicKey,
            equityOwnerName = zone
              .members(zone.accounts(zone.equityAccountId).ownerMemberIds.head)
              .name,
            equityOwnerMetadata = None,
            equityAccountName = None,
            equityAccountMetadata = None,
            name = zone.name,
            metadata = None
          ))
        CreateZoneResponse(zone.valid)
      else fail()
    )

  override protected[this] def execZoneCommand(
      zoneId: ZoneId,
      remoteAddress: InetAddress,
      publicKey: PublicKey,
      zoneCommand: ZoneCommand): Future[ZoneResponse] =
    Future.successful(
      if (zoneId == zone.id &&
          remoteAddress == HttpControllerSpec.remoteAddress &&
          publicKey == HttpControllerSpec.publicKey &&
          zoneCommand == ChangeZoneNameCommand(name = None))
        ChangeZoneNameResponse(().valid)
      else fail()
    )

  override protected[this] def zoneNotificationSource(
      remoteAddress: InetAddress,
      publicKey: PublicKey,
      zoneId: ZoneId): Source[ZoneNotification, NotUsed] =
    if (zoneId != zone.id) Source.empty
    else Source(zoneNotifications)

  override protected[this] val pingInterval: FiniteDuration = 3.seconds

  override protected[this] def webSocketFlow(
      remoteAddress: InetAddress): Flow[WsMessage, WsMessage, NotUsed] =
    Flow[WsMessage]

}

object HttpControllerSpec {

  private val remoteAddress = InetAddress.getByName("192.0.2.0")
  private val (rsaPrivateKey: RSAPrivateKey, rsaPublicKey: RSAPublicKey) = {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(2048)
    val keyPair = keyPairGenerator.generateKeyPair
    (keyPair.getPrivate, keyPair.getPublic)
  }
  private val selfSignedJwt =
    JwtJson.encode(
      Json.obj(
        "sub" -> okio.ByteString.of(rsaPublicKey.getEncoded: _*).base64()
      ),
      rsaPrivateKey,
      JwtAlgorithm.RS256
    )
  private val publicKey = PublicKey(rsaPublicKey.getEncoded)
  private val zone = {
    val created = 1514156286183L
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
      expires = created + java.time.Duration.ofDays(30).toMillis,
      name = Some("Dave's Game"),
      metadata = None
    )
  }
  private val balances = Map(
    zone.equityAccountId -> BigDecimal(-5000),
    AccountId("1") -> BigDecimal(5000)
  )

  private val zoneNotifications = Seq(
    ZoneStateNotification(
      zone,
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
        value = BigDecimal(5000),
        creator = MemberId("0"),
        created = zone.created + 3000,
        description = Some("Jenny's Lottery Win"),
        metadata = None
      )
    )
  )

}
