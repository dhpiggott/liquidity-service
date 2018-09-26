package com.dhpcs.liquidity.server

import java.net.InetAddress
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec

import akka.NotUsed
import akka.actor.typed.ActorRefResolver
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
import com.dhpcs.liquidity.actor.protocol.zonemonitor.ActiveZoneSummary
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.server.HttpController._
import com.dhpcs.liquidity.ws.protocol.ProtoBindings._
import com.dhpcs.liquidity.ws.protocol._
import com.google.protobuf.CodedOutputStream
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.{JWKSet, KeyUse, RSAKey}
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.{JWSVerificationKeySelector, SecurityContext}
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport._
import play.api.libs.json.Json
import play.api.libs.json.Json.toJsFieldJsValueWrapper
import scalapb.json4s.JsonFormat
import scalapb.{GeneratedMessage, GeneratedMessageCompanion, Message}

import scala.collection.immutable.Seq
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal

trait HttpController {

  protected[this] def httpRoutes(enableClientRelay: Boolean)(
      implicit ec: ExecutionContext): Route =
    path("version")(version) ~
      path("status")(status) ~
      logRequestResult(("access-log", Logging.InfoLevel))(
        pathPrefix("akka-management")(administratorRealm(akkaManagement)) ~
          pathPrefix("diagnostics")(administratorRealm(diagnostics)) ~
          (if (enableClientRelay)
             extractClientIP(_.toOption match {
               case None =>
                 complete(
                   (InternalServerError, "Couldn't extract client IP.")
                 )

               case Some(remoteAddress) =>
                 pathPrefix("zone")(
                   authenticateSelfSignedJwt(
                     publicKey =>
                       zoneCommand(remoteAddress, publicKey) ~
                         zoneNotifications(remoteAddress, publicKey)
                   )
                 )
             })
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
      subject <- Option(signedJwt.getJWTClaimsSet.getSubject)
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

  private[this] def version: Route =
    get(
      complete(
        Json.obj(
          BuildInfo.toMap
            .mapValues(value => toJsFieldJsValueWrapper(value.toString))
            .toSeq: _*
        )
      )
    )

  private[this] def status(implicit ec: ExecutionContext): Route =
    get(
      complete(
        if (!isClusterHealthy)
          ServiceUnavailable
        else
          for (activeZoneSummaries <- getActiveZoneSummaries)
            yield
              Json.obj(
                "activeZones" -> Json.obj(
                  "count" -> activeZoneSummaries.size,
                  "zones" -> activeZoneSummaries.toSeq
                    .sortBy(_.zoneId.value)
                    .map {
                      case ActiveZoneSummary(zoneId,
                                             members,
                                             accounts,
                                             transactions,
                                             metadata,
                                             connectedClients) =>
                        Json.obj(
                          "zoneIdFingerprint" -> okio.ByteString
                            .encodeUtf8(zoneId.value)
                            .sha256
                            .hex,
                          "metadata" -> metadata
                            .map(JsonFormat.toJsonString)
                            .map(Json.parse),
                          "members" -> members,
                          "accounts" -> accounts,
                          "transactions" -> transactions,
                          "clientConnections" -> connectedClients.values
                            .groupBy(_.remoteAddress)
                            .toSeq
                            .sortBy {
                              case (remoteAddress, _) =>
                                remoteAddress.getHostAddress
                            }
                            .map {
                              case (remoteAddress, clientsAtHostAddress) =>
                                Json.obj(
                                  "hostAddressFingerprint" -> okio.ByteString
                                    .encodeUtf8(remoteAddress.getHostAddress)
                                    .sha256
                                    .hex,
                                  "count" -> clientsAtHostAddress.size,
                                  "clientsAtHostAddress" -> clientsAtHostAddress
                                    .groupBy(_.publicKey)
                                    .toSeq
                                    .sortBy {
                                      case (publicKey, _) =>
                                        publicKey.fingerprint
                                    }
                                    .map {
                                      case (publicKey, clientsWithPublicKey) =>
                                        Json.obj(
                                          "publicKeyFingerprint" -> publicKey.fingerprint,
                                          "count" -> clientsWithPublicKey.size,
                                          "clientsWithPublicKey" -> Json.obj(
                                            "count" -> clientsWithPublicKey.size,
                                            "connectionIds" -> clientsWithPublicKey
                                              .map(clientsWithPublicKey =>
                                                resolver
                                                  .toSerializationFormat(
                                                    clientsWithPublicKey.connectionId))
                                              .toSeq
                                              .sorted
                                          )
                                        )
                                    }
                                )
                            }
                        )
                    }
                )
              )
      )
    )

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
              createZone(remoteAddress, publicKey, createZoneCommand)
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
                      execZoneCommand(zoneId,
                                      remoteAddress,
                                      publicKey,
                                      zoneCommand).map(
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
                pingInterval,
                () =>
                  ProtoBinding[ZoneNotification,
                               proto.ws.protocol.ZoneNotification,
                               Any].asProto(PingNotification())(()).asMessage
              )
        )
      )
    )

  protected[this] def isAdministrator(publicKey: PublicKey): Future[Boolean]

  protected[this] def akkaManagement: StandardRoute

  protected[this] def events(persistenceId: String,
                             fromSequenceNr: Long,
                             toSequenceNr: Long): Source[EventEnvelope, NotUsed]

  protected[this] def zoneState(
      zoneId: ZoneId): Future[proto.persistence.zone.ZoneState]

  protected[this] def isClusterHealthy: Boolean

  protected[this] def resolver: ActorRefResolver

  protected[this] def getActiveZoneSummaries: Future[Set[ActiveZoneSummary]]

  protected[this] def createZone(
      remoteAddress: InetAddress,
      publicKey: PublicKey,
      createZoneCommand: CreateZoneCommand): Future[ZoneResponse]

  protected[this] def execZoneCommand(
      zoneId: ZoneId,
      remoteAddress: InetAddress,
      publicKey: PublicKey,
      zoneCommand: ZoneCommand): Future[ZoneResponse]

  protected[this] def zoneNotificationSource(
      remoteAddress: InetAddress,
      publicKey: PublicKey,
      zoneId: ZoneId): Source[ZoneNotification, NotUsed]

  protected[this] def pingInterval: FiniteDuration

}

object HttpController {

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
    Marshaller
      .stringMarshaller(MediaTypes.`application/json`)
      .compose(
        eventEnvelope =>
          Json.prettyPrint(
            Json.obj(
              "sequenceNr" -> eventEnvelope.sequenceNr,
              // ScalaPB gives us a way to get a json4s representation of
              // messages, but not play-json. So we parse the string form as a
              // play-json JsValue (doing this, as opposed to just passing it
              // through as a string means that it will get marshaled by
              // akka-http-play-json, which by default will _pretty_ print it -
              // and we really want that, given this is purely a diagnostics
              // endpoint and should thus be developer-readable).
              "event" -> Json.parse(
                JsonFormat.toJsonString(eventEnvelope.event)
              )
            )
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

  implicit def optionResponseMarshaller[A](
      implicit entityMarshaller: ToEntityMarshaller[Option[A]])
    : ToResponseMarshaller[Option[A]] =
    Marshaller[Option[A], HttpResponse](
      implicit ec =>
        entity =>
          Marshaller.fromToEntityMarshaller(status =
            if (entity.isEmpty) NotFound else OK)(entityMarshaller)(entity)
    )

  private val zoneIdMatcher = JavaUUID.map(uuid => ZoneId(uuid.toString))

}
