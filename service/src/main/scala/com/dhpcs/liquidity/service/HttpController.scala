package com.dhpcs.liquidity.service

import java.net.InetAddress
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

import akka.NotUsed
import akka.event.Logging
import akka.http.scaladsl.common._
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling._
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Source
import akka.util.ByteString
import de.heikoseeberger.akkahttpjson4s.Json4sSupport._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.service.HttpController._
import com.dhpcs.liquidity.ws.protocol.ProtoBindings._
import com.dhpcs.liquidity.ws.protocol._
import com.google.protobuf.CodedOutputStream
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.{JWKSet, KeyUse, RSAKey}
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.{JWSVerificationKeySelector, SecurityContext}
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import org.json4s._
import scalapb.json4s.JsonFormat
import scalapb.{GeneratedMessage, GeneratedMessageCompanion, Message}

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
                             ZoneId) => Source[ZoneNotification, NotUsed]) {

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
           else reject)
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
        entity(as[proto.ws.protocol.CreateZoneCommand]) {
          protoCreateZoneCommand =>
            val createZoneCommand =
              ProtoBinding[CreateZoneCommand,
                           proto.ws.protocol.CreateZoneCommand,
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
                                 proto.ws.protocol.ZoneResponse,
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
            entity(as[proto.ws.protocol.ZoneCommandMessage]) {
              protoZoneCommandMessage =>
                val zoneCommand =
                  ProtoBinding[ZoneCommand, proto.ws.protocol.ZoneCommand, Any]
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
                                         proto.ws.protocol.ZoneResponse,
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
                               proto.ws.protocol.ZoneNotification,
                               Any].asProto(zoneNotification)(()).asMessage
              )
              .keepAlive(
                5.seconds,
                () =>
                  ProtoBinding[ZoneNotification,
                               proto.ws.protocol.ZoneNotification,
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
