package com.dhpcs.liquidity.service

import java.net.InetAddress
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.{InvalidKeySpecException, X509EncodedKeySpec}
import java.text.ParseException
import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.grpc.scaladsl.{Metadata, ServiceHandler}
import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import cats.data.{NonEmptyList, Validated}
import de.heikoseeberger.akkahttpjson4s.Json4sSupport._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.model.ProtoBindings._
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.proto.binding.ProtoBindings._
import com.dhpcs.liquidity.proto.grpc.protocol
import com.dhpcs.liquidity.proto.grpc.protocol.{
  LiquidityServicePowerApi,
  LiquidityServicePowerApiHandler
}
import com.dhpcs.liquidity.service.HttpController._
import com.dhpcs.liquidity.ws.protocol._
import com.dhpcs.liquidity.ws.protocol.ProtoBindings._
import com.nimbusds.jose.{JOSEException, JWSAlgorithm}
import com.nimbusds.jose.jwk.{JWKSet, KeyUse, RSAKey}
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.{
  BadJOSEException,
  JWSVerificationKeySelector,
  SecurityContext
}
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import org.json4s._
import scalaz.zio._

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.reflect.ClassTag

class HttpController(
    ready: Route,
    execZoneCommand: (InetAddress,
                      PublicKey,
                      ZoneId,
                      ZoneCommand) => Future[ZoneResponse],
    zoneNotificationSource: (InetAddress,
                             PublicKey,
                             ZoneId) => Source[ZoneNotification, NotUsed],
    runtime: Runtime[Any])(implicit system: ActorSystem, mat: Materializer) {

  def handler(
      enableClientRelay: Boolean): HttpRequest => Future[HttpResponse] = {
    ServiceHandler.concatOrNotFound(
      grpcHandler(enableClientRelay),
      { case request => restHandler(request) }
    )
  }

  private[this] def grpcHandler(enableClientRelay: Boolean)
    : PartialFunction[HttpRequest, Future[HttpResponse]] =
    if (enableClientRelay)
      LiquidityServicePowerApiHandler.partial(liquidityService)
    else
      PartialFunction.empty

  private[this] object liquidityService extends LiquidityServicePowerApi {

    override def createZone(
        in: protocol.CreateZoneCommand,
        metadata: Metadata): Future[protocol.CreateZoneResponse] =
      exec[CreateZoneCommand,
           protocol.CreateZoneCommand,
           CreateZoneResponse,
           protocol.CreateZoneResponse](
        zoneId = UUID.randomUUID().toString,
        in,
        metadata
      )(errors => CreateZoneResponse(Validated.invalid(errors)))

    override def changeZoneName(
        in: protocol.ChangeZoneNameCommand,
        metadata: Metadata): Future[protocol.ChangeZoneNameResponse] =
      exec[ChangeZoneNameCommand,
           protocol.ChangeZoneNameCommand,
           ChangeZoneNameResponse,
           protocol.ChangeZoneNameResponse](in.zoneId, in, metadata)(errors =>
        ChangeZoneNameResponse(Validated.invalid(errors)))

    override def createMember(
        in: protocol.CreateMemberCommand,
        metadata: Metadata): Future[protocol.CreateMemberResponse] =
      exec[CreateMemberCommand,
           protocol.CreateMemberCommand,
           CreateMemberResponse,
           protocol.CreateMemberResponse](in.zoneId, in, metadata)(errors =>
        CreateMemberResponse(Validated.invalid(errors)))

    override def updateMember(
        in: protocol.UpdateMemberCommand,
        metadata: Metadata): Future[protocol.UpdateMemberResponse] =
      exec[UpdateMemberCommand,
           protocol.UpdateMemberCommand,
           UpdateMemberResponse,
           protocol.UpdateMemberResponse](in.zoneId, in, metadata)(errors =>
        UpdateMemberResponse(Validated.invalid(errors)))

    override def createAccount(
        in: protocol.CreateAccountCommand,
        metadata: Metadata): Future[protocol.CreateAccountResponse] =
      exec[CreateAccountCommand,
           protocol.CreateAccountCommand,
           CreateAccountResponse,
           protocol.CreateAccountResponse](in.zoneId, in, metadata)(errors =>
        CreateAccountResponse(Validated.invalid(errors)))

    override def updateAccount(
        in: protocol.UpdateAccountCommand,
        metadata: Metadata): Future[protocol.UpdateAccountResponse] =
      exec[UpdateAccountCommand,
           protocol.UpdateAccountCommand,
           UpdateAccountResponse,
           protocol.UpdateAccountResponse](in.zoneId, in, metadata)(errors =>
        UpdateAccountResponse(Validated.invalid(errors)))

    override def addTransaction(
        in: protocol.AddTransactionCommand,
        metadata: Metadata): Future[protocol.AddTransactionResponse] =
      exec[AddTransactionCommand,
           protocol.AddTransactionCommand,
           AddTransactionResponse,
           protocol.AddTransactionResponse](in.zoneId, in, metadata)(errors =>
        AddTransactionResponse(Validated.invalid(errors)))

    override def zoneNotifications(
        in: protocol.ZoneSubscription,
        metadata: Metadata): Source[protocol.ZoneNotificationMessage, NotUsed] =
      runtime.unsafeRun(
        (for {
          remoteAddress <- readRemoteAddress(metadata)
          publicKey <- authenticateSelfSignedJwt(metadata).mapError(
            NonEmptyList.one)
          zoneNotifications = zoneNotificationSource(
            remoteAddress,
            publicKey,
            ZoneId(in.zoneId)
          ).keepAlive(10.seconds, () => ZoneNotification.Empty)
        } yield zoneNotifications)
          .fold(
            errors =>
              Source.single(Errors(errors.map(error =>
                ZoneNotification.Error(error.code, error.description)))),
            identity
          )
          .map(
            _.map(
              ProtoBinding[ZoneNotification, protocol.ZoneNotification, Any]
                .asProto(_)(())
                .asMessage
            )
          )
      )

    private[this] def exec[SC <: ZoneCommand, PC, SR: ClassTag, PR](
        zoneId: String,
        in: PC,
        metadata: Metadata)(
        presentErrors: NonEmptyList[ZoneResponse.Error] => SR)(
        implicit cpb: ProtoBinding[SC, PC, Any],
        rpb: ProtoBinding[SR, PR, Any]
    ): Future[PR] =
      runtime.unsafeRunToFuture(
        (for {
          remoteAddress <- readRemoteAddress(metadata)
          publicKey <- authenticateSelfSignedJwt(metadata).mapError(
            NonEmptyList.one)
          sc = cpb.asScala(in)(())
          sr <- IO
            .fromFuture(_ =>
              execZoneCommand(remoteAddress, publicKey, ZoneId(zoneId), sc)
                .mapTo[SR])
            .orDie
        } yield sr).fold(presentErrors, identity).map(rpb.asProto(_)(()))
      )

    private[this] def readRemoteAddress(metadata: Metadata): UIO[InetAddress] =
      for (remoteAddress <- IO
             .fromOption(metadata.getText("Remote-Address"))
             .orDieWith(_ => new Error))
        yield
          InetAddress.getByName(
            Uri.Authority.parse(remoteAddress).host.address())

    private[this] def authenticateSelfSignedJwt(
        metadata: Metadata): IO[ZoneResponse.Error, PublicKey] =
      for {
        authorization <- IO
          .fromOption(
            metadata
              .getText("Authorization"))
          .mapError(_ => ZoneResponse.Error.authorizationNotPresentInMetadata)
          .map(HttpHeader.parse("Authorization", _))
        token <- authorization match {
          case ParsingResult.Error(error) =>
            IO.fail(ZoneResponse.Error.authorizationNotValid(error))

          case ParsingResult.Ok(header, _) =>
            header match {
              case Authorization(credentials) =>
                credentials match {
                  case OAuth2BearerToken(token) =>
                    IO.succeed(token)

                  case other =>
                    IO.fail(
                      ZoneResponse.Error.authorizationNotAnOAuth2BearerToken(
                        other)
                    )
                }
            }
        }
        signedJwt <- Task.effect(SignedJWT.parse(token)).refineOrDie {
          case _: ParseException =>
            ZoneResponse.Error.tokenNotASignedJwt
        }
        claims <- Task.effect(signedJwt.getJWTClaimsSet).refineOrDie {
          case _: ParseException =>
            ZoneResponse.Error.tokenPayloadMustBeJson
        }
        subject <- IO
          .fromOption(Option(claims.getSubject))
          .mapError(_ => ZoneResponse.Error.tokenClaimsMustContainASubject)
        rsaPublicKey <- Task
          .effect(
            KeyFactory
              .getInstance("RSA")
              .generatePublic(
                new X509EncodedKeySpec(
                  okio.ByteString.decodeBase64(subject).toByteArray
                )
              )
              .asInstanceOf[RSAPublicKey]
          )
          .refineOrDie {
            case _: InvalidKeySpecException =>
              ZoneResponse.Error.tokenSubjectMustBeAnRsaPublicKey
          }
        _ <- Task
          .effect {
            val jwtProcessor = new DefaultJWTProcessor[SecurityContext]()
            jwtProcessor.setJWSKeySelector(
              new JWSVerificationKeySelector(
                JWSAlgorithm.RS256,
                new ImmutableJWKSet(
                  new JWKSet(
                    new RSAKey.Builder(rsaPublicKey)
                      .keyUse(KeyUse.SIGNATURE)
                      .build()
                  )
                )
              )
            )
            // TODO: Validate timing separately
            jwtProcessor.process(signedJwt, null)
          }
          .refineOrDie {
            case _: BadJOSEException | _: JOSEException =>
              ZoneResponse.Error.tokenMustBeSignedBySubjectsPrivateKey
          }
      } yield PublicKey(rsaPublicKey.getEncoded)

  }

  private[this] def restHandler: HttpRequest => Future[HttpResponse] =
    Route.asyncHandler(restRoute)

  def restRoute: Route =
    path("ready")(ready) ~
      path("version")(version)

}

object HttpController {

  private[this] implicit val serialization: Serialization = native.Serialization
  private[this] implicit val formats: Formats = DefaultFormats
  private[this] implicit val shouldWritePretty: ShouldWritePretty =
    ShouldWritePretty.True

  private def version: Route =
    get(
      complete(
        JObject(
          BuildInfo.toMap
            .mapValues(value => JString(value.toString))
            .toSeq: _*
        )
      )
    )

}
