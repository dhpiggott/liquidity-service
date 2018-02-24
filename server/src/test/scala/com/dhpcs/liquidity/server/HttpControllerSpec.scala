package com.dhpcs.liquidity.server

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
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.model.{RemoteAddress, StatusCodes, Uri}
import akka.http.scaladsl.server.StandardRoute
import akka.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers
import akka.stream.scaladsl.{Flow, Source}
import akka.testkit.TestProbe
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
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport._
import okio.ByteString
import org.scalatest.FreeSpec
import pdi.jwt.{JwtAlgorithm, JwtJson}
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.collection.immutable.Seq
import scala.concurrent.Future

class HttpControllerSpec
    extends FreeSpec
    with HttpController
    with ScalatestRouteTest {

  "The HttpController" - {
    "rejects access" - {
      "when no bearer token is presented" in {
        val getRequest =
          RequestBuilding
            .Get("/akka-management")
        getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
          assert(status === StatusCodes.Unauthorized)
          assert(
            header[`WWW-Authenticate`].contains(
              `WWW-Authenticate`(
                HttpChallenges
                  .oAuth2(realm = "Administration")
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
                  .oAuth2(realm = "Administration")
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
                  .oAuth2(realm = "Administration")
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
                  .oAuth2(realm = "Administration")
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
                  .oAuth2(realm = "Administration")
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
          .withHeaders(Authorization(OAuth2BearerToken(administratorJwt)))
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
          .withHeaders(Authorization(OAuth2BearerToken(administratorJwt)))
        getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
          assert(status === StatusCodes.OK)
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
        val getRequest = RequestBuilding
          .Get(
            Uri.Empty.withPath(
              Uri.Path("/diagnostics/zone") / zone.id.value
            )
          )
          .withHeaders(Authorization(OAuth2BearerToken(administratorJwt)))
        getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
          assert(status === StatusCodes.OK)
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
              |    "expires" : "1516748286183",
              |    "name" : "Dave's Game"
              |  }
              |}
            """.stripMargin
            ))
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
            .withHeaders(Authorization(OAuth2BearerToken(administratorJwt)))
          getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
            assert(status === StatusCodes.NotFound)
          }
        }
        "with status 200 when the zone exists" in {
          val getRequest =
            RequestBuilding
              .Get(s"/analytics/zone/${zone.id.value}")
              .withHeaders(Authorization(OAuth2BearerToken(administratorJwt)))
          getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
            assert(status === StatusCodes.OK)
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
            .Get(s"/analytics/zone/${zone.id.value}/balances")
            .withHeaders(Authorization(OAuth2BearerToken(administratorJwt)))
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
            .Get(s"/analytics/zone/${zone.id.value}/client-sessions")
            .withHeaders(Authorization(OAuth2BearerToken(administratorJwt)))
        getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
          assert(status === StatusCodes.OK)
          assert(
            entityAs[JsValue] === Json.parse(
              s"""
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
              """.stripMargin
            ))
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
    "provides status information" in {
      val getRequest = RequestBuilding.Get("/status")
      getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
        assert(status === StatusCodes.OK)
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
              remoteAddress = Some(remoteAddress),
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

}

object HttpControllerSpec {

  private val remoteAddress = InetAddress.getByName("192.0.2.0")
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
    expires = created + java.time.Duration.ofDays(30).toMillis,
    name = Some("Dave's Game"),
    metadata = None
  )
  private val balances = Map(
    equityAccountId -> BigDecimal(-5000),
    AccountId("1") -> BigDecimal(5000)
  )

  private val (rsaPrivateKey: RSAPrivateKey, rsaPublicKey: RSAPublicKey) = {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(2048)
    val keyPair = keyPairGenerator.generateKeyPair
    (keyPair.getPrivate, keyPair.getPublic)
  }
  private val administratorJwt =
    JwtJson.encode(
      Json.obj(
        "sub" -> okio.ByteString.of(rsaPublicKey.getEncoded: _*).base64()
      ),
      rsaPrivateKey,
      JwtAlgorithm.RS256
    )

}
