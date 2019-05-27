package com.dhpcs.liquidity.service

import java.net.InetAddress
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.{InvalidKeySpecException, X509EncodedKeySpec}
import java.text.ParseException
import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.event.Logging
import akka.grpc.scaladsl.{Metadata, ServiceHandler}
import akka.http.scaladsl.common._
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling._
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.data.NonEmptyList
import de.heikoseeberger.akkahttpjson4s.Json4sSupport._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.model.ProtoBindings._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.proto.binding.ProtoBindings._
import com.dhpcs.liquidity.proto.grpc.protocol
import com.dhpcs.liquidity.proto.grpc.protocol.{
  LiquidityServicePowerApi,
  LiquidityServicePowerApiHandler
}
import com.dhpcs.liquidity.service.HttpController._
import com.dhpcs.liquidity.ws.protocol.ProtoBindings._
import com.dhpcs.liquidity.ws.protocol._
import com.google.protobuf.CodedOutputStream
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
import scalapb.json4s.JsonFormat
import scalapb.{GeneratedMessage, GeneratedMessageCompanion, Message}
import scalaz.zio._

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal

class HttpController(
    ready: Route,
    alive: Route,
    akkaManagement: Route,
    isAdministrator: PublicKey => Future[Boolean],
    events: (String, Long, Long) => Source[EventEnvelope, NotUsed],
    zoneState: ZoneId => Future[proto.persistence.zone.ZoneState],
    execZoneCommand: (InetAddress,
                      PublicKey,
                      ZoneId,
                      ZoneCommand) => Future[ZoneResponse],
    zoneNotificationSource: (InetAddress,
                             PublicKey,
                             ZoneId) => Source[ZoneNotification, NotUsed],
    runtime: Runtime[Any])(implicit system: ActorSystem, mat: Materializer) {

  def handler(enableClientRelay: Boolean)(
      implicit ec: ExecutionContext): HttpRequest => Future[HttpResponse] = {
    val grpc = grpcHandler(enableClientRelay)
    val rest = restHandler(enableClientRelay)
    ServiceHandler.concatOrNotFound(
      grpc,
      { case request => rest(request) }
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
        metadata: Metadata): Future[protocol.CreateZoneResponse] = {
      val zone = for {
        remoteAddress <- readRemoteAddress(metadata)
        publicKey <- authenticateSelfSignedJwt(metadata).mapError(
          NonEmptyList.one)
        createZoneCommand = CreateZoneCommand(
          equityOwnerPublicKey = PublicKey(in.equityOwnerPublicKey.toByteArray),
          equityOwnerName = in.equityOwnerName,
          equityOwnerMetadata = in.equityOwnerMetadata,
          equityAccountName = in.equityAccountName,
          equityAccountMetadata = in.equityAccountMetadata,
          name = in.name,
          metadata = in.metadata
        )
        zoneId = ZoneId(UUID.randomUUID().toString)
        createZoneResponse <- IO
          .fromFuture(
            _ =>
              execZoneCommand(remoteAddress,
                              publicKey,
                              zoneId,
                              createZoneCommand)
                .mapTo[CreateZoneResponse]
          )
          .orDie
        zone <- IO.fromEither(createZoneResponse.result.toEither)
      } yield zone
      runtime.unsafeRunToFuture(
        zone.fold(
          err =>
            proto.grpc.protocol.CreateZoneResponse(
              proto.grpc.protocol.CreateZoneResponse.Result.Errors(
                proto.grpc.protocol.Errors(
                  err
                    .map(error =>
                      proto.grpc.protocol.Errors
                        .Error(error.code, error.description))
                    .toList)
              )
          ),
          zone =>
            proto.grpc.protocol.CreateZoneResponse(
              proto.grpc.protocol.CreateZoneResponse.Result.Success(
                proto.grpc.protocol.CreateZoneResponse.Success(
                  Some(
                    ProtoBinding[Zone, proto.model.Zone, Any]
                      .asProto(zone)(())
                  ))
              )
          )
        )
      )
    }

    override def changeZoneName(
        in: protocol.ChangeZoneNameCommand,
        metadata: Metadata): Future[protocol.ChangeZoneNameResponse] = {
      val done = for {
        remoteAddress <- readRemoteAddress(metadata)
        publicKey <- authenticateSelfSignedJwt(metadata).mapError(
          NonEmptyList.one)
        changeZoneNameCommand = ChangeZoneNameCommand(
          name = in.name
        )
        changeZoneNameResponse <- IO
          .fromFuture(
            _ =>
              execZoneCommand(remoteAddress,
                              publicKey,
                              ZoneId(in.zoneId),
                              changeZoneNameCommand)
                .mapTo[ChangeZoneNameResponse])
          .orDie
        _ <- IO.fromEither(changeZoneNameResponse.result.toEither)
      } yield ()
      runtime.unsafeRunToFuture(
        done.fold(
          err =>
            proto.grpc.protocol.ChangeZoneNameResponse(
              proto.grpc.protocol.ChangeZoneNameResponse.Result.Errors(
                proto.grpc.protocol.Errors(
                  err
                    .map(error =>
                      proto.grpc.protocol.Errors
                        .Error(error.code, error.description))
                    .toList)
              )
          ),
          _ =>
            proto.grpc.protocol.ChangeZoneNameResponse(
              proto.grpc.protocol.ChangeZoneNameResponse.Result.Success(
                com.google.protobuf.empty.Empty.defaultInstance
              )
          )
        ))
    }

    override def createMember(
        in: protocol.CreateMemberCommand,
        metadata: Metadata): Future[protocol.CreateMemberResponse] = {
      val member = for {
        remoteAddress <- readRemoteAddress(metadata)
        publicKey <- authenticateSelfSignedJwt(metadata).mapError(
          NonEmptyList.one)
        createMemberCommand = CreateMemberCommand(
          in.ownerPublicKeys
            .map(ownerPublicKey => PublicKey(ownerPublicKey.toByteArray))
            .toSet,
          in.name,
          in.metadata
        )
        createMemberResponse <- IO
          .fromFuture(
            _ =>
              execZoneCommand(remoteAddress,
                              publicKey,
                              ZoneId(in.zoneId),
                              createMemberCommand)
                .mapTo[CreateMemberResponse])
          .orDie
        member <- IO.fromEither(createMemberResponse.result.toEither)
      } yield member
      runtime.unsafeRunToFuture(
        member.fold(
          err =>
            proto.grpc.protocol.CreateMemberResponse(
              proto.grpc.protocol.CreateMemberResponse.Result.Errors(
                proto.grpc.protocol.Errors(
                  err
                    .map(error =>
                      proto.grpc.protocol.Errors
                        .Error(error.code, error.description))
                    .toList)
              )
          ),
          member =>
            proto.grpc.protocol.CreateMemberResponse(
              proto.grpc.protocol.CreateMemberResponse.Result.Success(
                proto.grpc.protocol.CreateMemberResponse.Success(
                  Some(
                    ProtoBinding[Member, proto.model.Member, Any]
                      .asProto(member)(())
                  )))
          )
        )
      )
    }

    override def updateMember(
        in: protocol.UpdateMemberCommand,
        metadata: Metadata): Future[protocol.UpdateMemberResponse] = {
      val done = for {
        remoteAddress <- readRemoteAddress(metadata)
        publicKey <- authenticateSelfSignedJwt(metadata).mapError(
          NonEmptyList.one)
        updateMemberCommand = UpdateMemberCommand(
          member = ProtoBinding[Member, Option[proto.model.Member], Any]
            .asScala(in.member)(())
        )
        updateMemberResponse <- IO
          .fromFuture(
            _ =>
              execZoneCommand(remoteAddress,
                              publicKey,
                              ZoneId(in.zoneId),
                              updateMemberCommand)
                .mapTo[UpdateMemberResponse])
          .orDie
        _ <- IO.fromEither(updateMemberResponse.result.toEither)
      } yield ()
      runtime.unsafeRunToFuture(
        done.fold(
          err =>
            proto.grpc.protocol.UpdateMemberResponse(
              proto.grpc.protocol.UpdateMemberResponse.Result.Errors(
                proto.grpc.protocol.Errors(
                  err
                    .map(error =>
                      proto.grpc.protocol.Errors
                        .Error(error.code, error.description))
                    .toList)
              )
          ),
          _ =>
            proto.grpc.protocol.UpdateMemberResponse(
              proto.grpc.protocol.UpdateMemberResponse.Result.Success(
                com.google.protobuf.empty.Empty.defaultInstance
              )
          )
        )
      )
    }

    override def createAccount(
        in: protocol.CreateAccountCommand,
        metadata: Metadata): Future[protocol.CreateAccountResponse] = {
      val member = for {
        remoteAddress <- readRemoteAddress(metadata)
        publicKey <- authenticateSelfSignedJwt(metadata).mapError(
          NonEmptyList.one)
        createAccountCommand = CreateAccountCommand(
          in.ownerMemberIds
            .map(ownerMemberId => MemberId(ownerMemberId))
            .toSet,
          in.name,
          in.metadata
        )
        createAccountResponse <- IO
          .fromFuture(
            _ =>
              execZoneCommand(remoteAddress,
                              publicKey,
                              ZoneId(in.zoneId),
                              createAccountCommand)
                .mapTo[CreateAccountResponse])
          .orDie
        member <- IO.fromEither(createAccountResponse.result.toEither)
      } yield member
      runtime.unsafeRunToFuture(
        member.fold(
          err =>
            proto.grpc.protocol.CreateAccountResponse(
              proto.grpc.protocol.CreateAccountResponse.Result.Errors(
                proto.grpc.protocol.Errors(
                  err
                    .map(error =>
                      proto.grpc.protocol.Errors
                        .Error(error.code, error.description))
                    .toList)
              )
          ),
          member =>
            proto.grpc.protocol.CreateAccountResponse(
              proto.grpc.protocol.CreateAccountResponse.Result.Success(
                proto.grpc.protocol.CreateAccountResponse.Success(
                  Some(
                    ProtoBinding[Account, proto.model.Account, Any]
                      .asProto(member)(())
                  )))
          )
        )
      )
    }

    override def updateAccount(
        in: protocol.UpdateAccountCommand,
        metadata: Metadata): Future[protocol.UpdateAccountResponse] = {
      val done = for {
        remoteAddress <- readRemoteAddress(metadata)
        publicKey <- authenticateSelfSignedJwt(metadata).mapError(
          NonEmptyList.one)
        updateAccountCommand = UpdateAccountCommand(
          actingAs = MemberId(in.actingAs),
          account = ProtoBinding[Account, Option[proto.model.Account], Any]
            .asScala(in.account)(())
        )
        updateAccountResponse <- IO
          .fromFuture(
            _ =>
              execZoneCommand(remoteAddress,
                              publicKey,
                              ZoneId(in.zoneId),
                              updateAccountCommand)
                .mapTo[UpdateAccountResponse])
          .orDie
        _ <- IO.fromEither(updateAccountResponse.result.toEither)
      } yield ()
      runtime.unsafeRunToFuture(
        done.fold(
          err =>
            proto.grpc.protocol.UpdateAccountResponse(
              proto.grpc.protocol.UpdateAccountResponse.Result.Errors(
                proto.grpc.protocol.Errors(
                  err
                    .map(error =>
                      proto.grpc.protocol.Errors
                        .Error(error.code, error.description))
                    .toList)
              )
          ),
          _ =>
            proto.grpc.protocol.UpdateAccountResponse(
              proto.grpc.protocol.UpdateAccountResponse.Result.Success(
                com.google.protobuf.empty.Empty.defaultInstance
              )
          )
        )
      )
    }

    override def addTransaction(
        in: protocol.AddTransactionCommand,
        metadata: Metadata): Future[protocol.AddTransactionResponse] = {
      val transaction = for {
        remoteAddress <- readRemoteAddress(metadata)
        publicKey <- authenticateSelfSignedJwt(metadata).mapError(
          NonEmptyList.one)
        addTransactionCommand = AddTransactionCommand(
          MemberId(in.actingAs),
          AccountId(in.from),
          AccountId(in.to),
          BigDecimal(in.value),
          in.description,
          in.metadata
        )
        addTransactionResponse <- IO
          .fromFuture(
            _ =>
              execZoneCommand(remoteAddress,
                              publicKey,
                              ZoneId(in.zoneId),
                              addTransactionCommand)
                .mapTo[AddTransactionResponse])
          .orDie
        transaction <- IO.fromEither(addTransactionResponse.result.toEither)
      } yield transaction
      runtime.unsafeRunToFuture(
        transaction.fold(
          err =>
            proto.grpc.protocol.AddTransactionResponse(
              proto.grpc.protocol.AddTransactionResponse.Result.Errors(
                proto.grpc.protocol.Errors(
                  err
                    .map(error =>
                      proto.grpc.protocol.Errors
                        .Error(error.code, error.description))
                    .toList)
              )
          ),
          transaction =>
            proto.grpc.protocol.AddTransactionResponse(
              proto.grpc.protocol.AddTransactionResponse.Result.Success(
                proto.grpc.protocol.AddTransactionResponse.Success(
                  Some(
                    ProtoBinding[Transaction, proto.model.Transaction, Any]
                      .asProto(transaction)(())
                  )))
          )
        )
      )
    }

    override def zoneNotifications(in: protocol.ZoneSubscription,
                                   metadata: Metadata)
      : Source[protocol.ZoneNotificationMessage, NotUsed] = {
      val zoneNotifications = for {
        remoteAddress <- readRemoteAddress(metadata)
        publicKey <- authenticateSelfSignedJwt(metadata).mapError(
          NonEmptyList.one)
        zoneNotifications = zoneNotificationSource(remoteAddress,
                                                   publicKey,
                                                   ZoneId(in.zoneId))
          .keepAlive(10.seconds, () => ZoneNotification.Empty)
          .map {
            case ZoneNotification.Empty =>
              proto.grpc.protocol.ZoneNotification.Empty.asMessage

            case PingNotification() =>
              throw new Error

            case zoneStateNotification: ZoneStateNotification =>
              protocol
                .ZoneStateNotification(
                  zoneStateNotification.zone.map(
                    ProtoBinding[Zone, proto.model.Zone, Any]
                      .asProto(_)(())
                  ),
                  zoneStateNotification.connectedClients.mapValues(
                    ProtoBinding[PublicKey, com.google.protobuf.ByteString, Any]
                      .asProto(_)(())
                  )
                )
                .asMessage

            case clientJoinedNotification: ClientJoinedNotification =>
              protocol
                .ClientJoinedZoneNotification(
                  clientJoinedNotification.connectionId,
                  ProtoBinding[PublicKey, com.google.protobuf.ByteString, Any]
                    .asProto(publicKey)(())
                )
                .asMessage

            case clientQuitNotification: ClientQuitNotification =>
              protocol
                .ClientQuitZoneNotification(
                  clientQuitNotification.connectionId,
                  ProtoBinding[PublicKey, com.google.protobuf.ByteString, Any]
                    .asProto(clientQuitNotification.publicKey)(())
                )
                .asMessage

            case zoneNameChangedNotification: ZoneNameChangedNotification =>
              protocol
                .ZoneNameChangedNotification(zoneNameChangedNotification.name)
                .asMessage

            case memberCreatedNotification: MemberCreatedNotification =>
              protocol
                .MemberCreatedNotification(
                  Some(
                    ProtoBinding[Member, proto.model.Member, Any]
                      .asProto(memberCreatedNotification.member)(())
                  )
                )
                .asMessage

            case memberUpdatedNotification: MemberUpdatedNotification =>
              protocol
                .MemberUpdatedNotification(
                  Some(
                    ProtoBinding[Member, proto.model.Member, Any]
                      .asProto(memberUpdatedNotification.member)(())
                  )
                )
                .asMessage

            case accountCreatedNotification: AccountCreatedNotification =>
              protocol
                .AccountCreatedNotification(
                  Some(
                    ProtoBinding[Account, proto.model.Account, Any]
                      .asProto(accountCreatedNotification.account)(())
                  )
                )
                .asMessage

            case accountUpdatedNotification: AccountUpdatedNotification =>
              protocol
                .AccountUpdatedNotification(
                  accountUpdatedNotification.actingAs.value,
                  Some(
                    ProtoBinding[Account, proto.model.Account, Any]
                      .asProto(accountUpdatedNotification.account)(())
                  )
                )
                .asMessage

            case transactionAddedNotification: TransactionAddedNotification =>
              protocol
                .TransactionAddedNotification(
                  Some(
                    ProtoBinding[Transaction, proto.model.Transaction, Any]
                      .asProto(transactionAddedNotification.transaction)(())
                  )
                )
                .asMessage
          }
      } yield zoneNotifications
      runtime.unsafeRun(
        zoneNotifications.fold(
          err =>
            Source.single(
              proto.grpc.protocol
                .Errors(
                  err
                    .map(error =>
                      proto.grpc.protocol.Errors
                        .Error(error.code, error.description))
                    .toList)
                .asMessage
          ),
          zoneNotifications => zoneNotifications
        )
      )
    }

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

  private[this] def restHandler(enableClientRelay: Boolean)(
      implicit ec: ExecutionContext): HttpRequest => Future[HttpResponse] =
    Route.asyncHandler(route(enableClientRelay))

  def route(enableClientRelay: Boolean)(implicit ec: ExecutionContext): Route =
    path("ready")(ready) ~
      path("alive")(alive) ~
      logRequestResult(("access-log", Logging.InfoLevel))(
        path("version")(version) ~
          pathPrefix("akka-management")(administratorRealm(akkaManagement)) ~
          pathPrefix("diagnostics")(administratorRealm(diagnostics)) ~
          (if (enableClientRelay)
             pathPrefix("zone")(
               extractClientIP(_.toOption match {
                 case None =>
                   complete(
                     (InternalServerError, "Couldn't extract client IP.")
                   )

                 case Some(remoteAddress) =>
                   authenticateSelfSignedJwt(
                     publicKey =>
                       zoneCommand(remoteAddress, publicKey) ~
                         zoneNotifications(remoteAddress, publicKey)
                   )
               })
             )
           else
             reject)
      )

  private[this] def administratorRealm: Directive0 =
    for {
      publicKey <- authenticateSelfSignedJwt
      _ <- authorizeByPublicKey(publicKey)
    } yield ()

  private[this] def authenticateSelfSignedJwt: Directive1[PublicKey] =
    for {
      credentials <- extractCredentials
      token <- credentials match {
        case Some(OAuth2BearerToken(value)) =>
          provide(value)

        case _ =>
          unauthorized[String]("Bearer token authorization must be presented.")
      }
      signedJwt <- Try(SignedJWT.parse(token))
        .map(provide)
        .getOrElse(unauthorized("Token must be a signed JWT."))
      claims <- Try(signedJwt.getJWTClaimsSet)
        .map(provide)
        .getOrElse(unauthorized("Token payload must be JSON."))
      subject <- Option(claims.getSubject)
        .map(provide)
        .getOrElse(unauthorized("Token claims must contain a subject."))
      rsaPublicKey <- Try(
        KeyFactory
          .getInstance("RSA")
          .generatePublic(
            new X509EncodedKeySpec(
              okio.ByteString.decodeBase64(subject).toByteArray
            )
          )
          .asInstanceOf[RSAPublicKey]
      ).map(provide)
        .getOrElse(unauthorized("Token subject must be an RSA public key."))
      _ <- Try {
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
        jwtProcessor.process(signedJwt, null)
      }.map(provide)
        .getOrElse(
          unauthorized(
            "Token must be signed by subject's private key and used " +
              "between nbf and iat claims.")
        )
    } yield PublicKey(rsaPublicKey.getEncoded)

  private[this] def authorizeByPublicKey(
      publicKey: PublicKey): Directive1[PublicKey] =
    onSuccess(isAdministrator(publicKey)).flatMap { isAdministrator =>
      if (isAdministrator) provide(publicKey)
      else forbidden
    }

  private[this] def diagnostics: Route =
    path("events" / Segment)(
      persistenceId =>
        parameters(("fromSequenceNr".as[Long] ? 0L,
                    "toSequenceNr".as[Long] ? Long.MaxValue)) {
          (fromSequenceNr, toSequenceNr) =>
            get(complete(events(persistenceId, fromSequenceNr, toSequenceNr)))
      }) ~
      path("zone" / zoneIdMatcher)(zoneId => get(complete(zoneState(zoneId))))

  private[this] def zoneCommand(
      remoteAddress: InetAddress,
      publicKey: PublicKey)(implicit ec: ExecutionContext): Route =
    put(
      pathEnd(
        entity(as[proto.rest.protocol.CreateZoneCommand]) {
          protoCreateZoneCommand =>
            val createZoneCommand =
              ProtoBinding[CreateZoneCommand,
                           proto.rest.protocol.CreateZoneCommand,
                           Any].asScala(
                protoCreateZoneCommand
              )(())
            complete(
              execZoneCommand(remoteAddress,
                              publicKey,
                              ZoneId(UUID.randomUUID().toString),
                              createZoneCommand)
                .map(
                  zoneResponse =>
                    ProtoBinding[ZoneResponse,
                                 proto.rest.protocol.ZoneResponse,
                                 Any]
                      .asProto(
                        zoneResponse
                      )(())
                      .asMessage)
            )
        }
      ) ~
        path(zoneIdMatcher)(
          zoneId =>
            entity(as[proto.rest.protocol.ZoneCommandMessage]) {
              protoZoneCommandMessage =>
                val zoneCommand =
                  ProtoBinding[ZoneCommand,
                               proto.rest.protocol.ZoneCommand,
                               Any]
                    .asScala(
                      protoZoneCommandMessage.toZoneCommand
                    )(())
                zoneCommand match {
                  case _: CreateZoneCommand =>
                    reject(
                      ValidationRejection(
                        "Zone ID cannot be specified with CreateZoneCommands"
                      )
                    )

                  case _ =>
                    complete(
                      execZoneCommand(remoteAddress,
                                      publicKey,
                                      zoneId,
                                      zoneCommand)
                        .map(
                          zoneResponse =>
                            ProtoBinding[ZoneResponse,
                                         proto.rest.protocol.ZoneResponse,
                                         Any]
                              .asProto(
                                zoneResponse
                              )(())
                              .asMessage)
                    )
                }
          }
        )
    )

  private[this] def zoneNotifications(remoteAddress: InetAddress,
                                      publicKey: PublicKey): Route =
    get(
      path(zoneIdMatcher)(
        zoneId =>
          complete(
            zoneNotificationSource(remoteAddress, publicKey, zoneId)
              .map(
                zoneNotification =>
                  ProtoBinding[ZoneNotification,
                               proto.rest.protocol.ZoneNotification,
                               Any].asProto(zoneNotification)(()).asMessage
              )
              .keepAlive(
                10.seconds,
                () =>
                  ProtoBinding[ZoneNotification,
                               proto.rest.protocol.ZoneNotification,
                               Any].asProto(PingNotification())(()).asMessage
              )
        )
      )
    )

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

  private def unauthorized[A](error: String): Directive1[A] =
    complete(
      (
        Unauthorized,
        Seq(
          `WWW-Authenticate`(
            HttpChallenges
              .oAuth2(realm = null)
              .copy(params = Map("error" -> error))
          )
        )
      )
    )

  private def forbidden[A]: Directive1[A] =
    complete(Forbidden)

  final case class EventEnvelope(sequenceNr: Long, event: GeneratedMessage)

  implicit val eventEnvelopeEntityMarshaller
    : ToEntityMarshaller[EventEnvelope] =
    marshaller[JValue]
      .compose(
        eventEnvelope =>
          JObject(
            "sequenceNr" -> JLong(eventEnvelope.sequenceNr),
            "event" -> JsonFormat.toJson(eventEnvelope.event)
        )
      )

  implicit def generatedMessageSourceResponseMarshaller
    : ToResponseMarshaller[Source[GeneratedMessage, NotUsed]] =
    Marshaller.oneOf(
      PredefinedToResponseMarshallers.fromEntityStreamingSupportAndEntityMarshaller,
      Marshaller[Source[GeneratedMessage, NotUsed], HttpResponse](
        _ =>
          source =>
            FastFuture.successful(List(Marshalling.WithFixedContentType(
              ContentType(
                MediaType.customBinary(mainType = "application",
                                       subType = "x-protobuf",
                                       comp = MediaType.NotCompressible,
                                       params = Map("delimited" -> "true"))
              ),
              () =>
                HttpResponse(
                  entity = HttpEntity(
                    ContentType(
                      MediaType.customBinary(
                        mainType = "application",
                        subType = "x-protobuf",
                        comp = MediaType.NotCompressible,
                        params = Map("delimited" -> "true"))
                    ),
                    source.map { generatedMessage =>
                      val byteString = ByteString.newBuilder
                      byteString.sizeHint(
                        CodedOutputStream.computeUInt32SizeNoTag(
                          generatedMessage.serializedSize) + generatedMessage.serializedSize
                      )
                      generatedMessage.writeDelimitedTo(
                        byteString.asOutputStream
                      )
                      byteString.result()
                    }
                  )
              )
            ))))
    )

  implicit val jsonEntityStreamingSupport: JsonEntityStreamingSupport =
    EntityStreamingSupport.json()

  implicit val generatedMessageEntityMarshaller
    : ToEntityMarshaller[GeneratedMessage] =
    Marshaller
      .oneOf(
        Marshaller
          .stringMarshaller(MediaTypes.`application/json`)
          .compose(JsonFormat.toJsonString),
        Marshaller
          .byteArrayMarshaller(
            ContentType(
              MediaType.customBinary(mainType = "application",
                                     subType = "x-protobuf",
                                     comp = MediaType.NotCompressible)
            )
          )
          .compose(_.toByteArray)
      )

  implicit def generatedMessageEntityUnmarshaller[
      A <: GeneratedMessage with Message[A]](
      implicit generatedMessageCompanion: GeneratedMessageCompanion[A])
    : FromEntityUnmarshaller[A] =
    Unmarshaller.firstOf(
      Unmarshaller.stringUnmarshaller
        .forContentTypes(ContentTypeRange(MediaTypes.`application/json`))
        .map(
          try JsonFormat.fromJsonString[A]
          catch {
            case NonFatal(e) =>
              throw RejectionError(ValidationRejection(e.getMessage, Some(e)))
          }
        ),
      Unmarshaller.byteStringUnmarshaller
        .forContentTypes(
          ContentTypeRange(
            ContentType(
              MediaType.customBinary(mainType = "application",
                                     subType = "x-protobuf",
                                     comp = MediaType.NotCompressible)
            )
          )
        )
        .map(byteString =>
          generatedMessageCompanion.parseFrom(byteString.toArray))
    )

  private val zoneIdMatcher = JavaUUID.map(uuid => ZoneId(uuid.toString))

}
