package com.dhpcs.liquidity.server

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}

import cats.data.Validated.{Invalid, Valid}
import cats.data.ValidatedNel
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.ws.protocol.ProtoBindings._
import com.dhpcs.liquidity.ws.protocol._
import com.google.protobuf.struct.{Struct, Value}
import io.gatling.core.Predef._
import io.gatling.http.Predef._
import io.gatling.http.check.HttpCheck
import io.gatling.http.funspec.GatlingHttpFunSpec
import io.gatling.http.protocol.HttpProtocolBuilder
import io.gatling.http.request.builder.HttpRequestBuilder
import pdi.jwt.{JwtAlgorithm, JwtJson}
import play.api.libs.json.Json

class LiquidityClientSimulation extends GatlingHttpFunSpec {

  override val baseURL: String =
    s"https://${sys.env.getOrElse("DOMAIN_PREFIX", "")}api.liquidityapp.com"

  override def httpConf: HttpProtocolBuilder =
    super.httpConf
      .acceptEncodingHeader("gzip")
      .userAgentHeader("client-simulation")

  private[this] val (rsaPrivateKey: RSAPrivateKey, rsaPublicKey: RSAPublicKey) = {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(2048)
    val keyPair = keyPairGenerator.generateKeyPair
    (keyPair.getPrivate, keyPair.getPublic)
  }
  private[this] val selfSignedJwt =
    JwtJson.encode(
      Json.obj(
        "sub" -> okio.ByteString.of(rsaPublicKey.getEncoded: _*).base64()
      ),
      rsaPrivateKey,
      JwtAlgorithm.RS256
    )

  // TODO: Use the event-stream to subscribe to notifications and update the
  // zone accordingly.
  // TODO: Simulate member-account pairs being created by independent users,
  // and seed money being sent by the equity owner user in response to each
  // creating their first account.

  spec {
    createZone(resultKey = "zone")
  }
  spec {
    createMember(
      zoneId = _("zone").as[Zone].id
    )(resultKey = "member")
  }
  spec {
    createAccount(
      zoneId = _("zone").as[Zone].id,
      ownerMemberIds = session => Set(session("member").as[Member].id)
    )(resultKey = "account")
  }
  spec {
    addTransaction(
      zoneId = _("zone").as[Zone].id,
      actingAs = session => {
        val zone = session("zone").as[Zone]
        zone.accounts(zone.equityAccountId).ownerMemberIds.head
      },
      from = _("zone").as[Zone].equityAccountId,
      to = _("account").as[Account].id,
      value = BigDecimal(5000)
    )(resultKey = "transaction")
  }

  private[this] def createZone(resultKey: String): HttpRequestBuilder =
    execZoneCommand(
      requestName = "Create zone",
      zoneSubPath = (_: Session) => "",
      (_: Session) =>
        ProtoBinding[CreateZoneCommand,
                     proto.ws.protocol.ZoneCommand.CreateZoneCommand,
                     Any]
          .asProto(
            CreateZoneCommand(
              equityOwnerPublicKey = PublicKey(rsaPublicKey.getEncoded),
              equityOwnerName = Some("Test"),
              equityOwnerMetadata = None,
              equityAccountName = None,
              equityAccountMetadata = None,
              name = Some("Test"),
              metadata = Some(
                Struct(
                  Map(
                    "isTest" -> Value.defaultInstance.withBoolValue(true)
                  )
                )
              )
            )
          )(())
          .toByteArray
    ).check(
      extractAndSaveZoneResponse[CreateZoneResponse, Zone](_.result, resultKey)
    )

  private[this] def createMember(zoneId: Session => ZoneId)(
      resultKey: String): HttpRequestBuilder =
    protoBindAndExecZoneCommand("Create member")(
      zoneSubPath = (session: Session) =>
        s"/${URLEncoder.encode(zoneId(session).value, StandardCharsets.UTF_8.name())}",
      (_: Session) =>
        CreateMemberCommand(
          ownerPublicKeys = Set(PublicKey(rsaPublicKey.getEncoded)),
          name = Some("Test"),
          metadata = Some(
            Struct(
              Map(
                "isTest" -> Value.defaultInstance.withBoolValue(true)
              )
            )
          )
      )
    ).check(
      extractAndSaveZoneResponse[CreateMemberResponse, Member](_.result,
                                                               resultKey)
    )

  private[this] def createAccount(zoneId: Session => ZoneId,
                                  ownerMemberIds: Session => Set[MemberId])(
      resultKey: String): HttpRequestBuilder =
    protoBindAndExecZoneCommand("Create account")(
      zoneSubPath = (session: Session) =>
        s"/${URLEncoder.encode(zoneId(session).value, StandardCharsets.UTF_8.name())}",
      (session: Session) =>
        CreateAccountCommand(
          ownerMemberIds(session),
          name = Some("Test"),
          metadata = Some(
            Struct(
              Map(
                "isTest" -> Value.defaultInstance.withBoolValue(true)
              )
            )
          )
      )
    ).check(
      extractAndSaveZoneResponse[CreateAccountResponse, Account](_.result,
                                                                 resultKey)
    )

  private[this] def addTransaction(
      zoneId: Session => ZoneId,
      actingAs: Session => MemberId,
      from: Session => AccountId,
      to: Session => AccountId,
      value: BigDecimal)(resultKey: String): HttpRequestBuilder =
    protoBindAndExecZoneCommand("Add transaction")(
      zoneSubPath = (session: Session) =>
        s"/${URLEncoder.encode(zoneId(session).value, StandardCharsets.UTF_8.name())}",
      (session: Session) =>
        AddTransactionCommand(
          actingAs(session),
          from(session),
          to(session),
          value,
          description = Some("Test"),
          metadata = Some(
            Struct(
              Map(
                "isTest" -> Value.defaultInstance.withBoolValue(true)
              )
            )
          )
      )
    ).check(
      extractAndSaveZoneResponse[AddTransactionResponse, Transaction](_.result,
                                                                      resultKey)
    )

  private[this] def protoBindAndExecZoneCommand(requestName: String)(
      zoneSubPath: Session => String,
      zoneCommand: Session => ZoneCommand): HttpRequestBuilder =
    execZoneCommand(
      requestName,
      zoneSubPath,
      (session: Session) =>
        ProtoBinding[ZoneCommand, proto.ws.protocol.ZoneCommand, Any]
          .asProto(zoneCommand(session))(())
          .toByteArray
    )

  private[this] def execZoneCommand(
      requestName: String,
      zoneSubPath: Session => String,
      entity: Session => Array[Byte]): HttpRequestBuilder =
    http(requestName)
      .put(session => s"/zone${zoneSubPath(session)}")
      .header(HttpHeaderNames.Authorization, s"Bearer $selfSignedJwt")
      .header(HttpHeaderNames.Accept, "application/x-protobuf")
      .header(HttpHeaderNames.ContentType, "application/x-protobuf")
      .body(ByteArrayBody(entity(_)))
      .check(
        status.is(200),
        header(HttpHeaderNames.ContentType).is("application/x-protobuf")
      )

  private[this] def extractAndSaveZoneResponse[A, B](
      extractResult: A => ValidatedNel[Any, B],
      resultKey: String): HttpCheck =
    bodyBytes
      .transform(
        bodyBytes =>
          extractResult(
            ProtoBinding[ZoneResponse, proto.ws.protocol.ZoneResponse, Any]
              .asScala(proto.ws.protocol.ZoneResponse.parseFrom(bodyBytes))(())
              .asInstanceOf[A]
          ) match {
            case Invalid(e) => throw new IllegalArgumentException(e.toString())
            case Valid(a)   => a
        }
      )
      .saveAs(resultKey)

}
