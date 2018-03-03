package com.dhpcs.liquidity.server

import java.net.InetAddress
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec

import akka.NotUsed
import akka.http.scaladsl.common._
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.ws.{Message => WsMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling._
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Flow, Source}
import akka.util.ByteString
import cats.instances.future._
import cats.syntax.apply._
import com.dhpcs.liquidity.actor.protocol.clientmonitor.ActiveClientSummary
import com.dhpcs.liquidity.actor.protocol.zonemonitor.ActiveZoneSummary
import com.dhpcs.liquidity.model.ProtoBindings._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.server.HttpController._
import com.dhpcs.liquidity.server.SqlAnalyticsStore.ClientSessionsStore._
import com.dhpcs.liquidity.ws.protocol.ProtoBindings._
import com.dhpcs.liquidity.ws.protocol._
import com.google.protobuf.CodedOutputStream
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport._
import pdi.jwt.{JwtAlgorithm, JwtJson, JwtOptions}
import play.api.libs.json.Json
import play.api.libs.json.Json.toJsFieldJsValueWrapper
import scalapb.json4s.JsonFormat
import scalapb.{GeneratedMessage, GeneratedMessageCompanion, Message}

import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal

trait HttpController {

  protected[this] def httpRoutes(enableClientRelay: Boolean)(
      implicit ec: ExecutionContext): Route =
    pathPrefix("akka-management")(administratorRealm(akkaManagement)) ~
      pathPrefix("diagnostics")(administratorRealm(diagnostics)) ~
      pathPrefix("analytics")(administratorRealm(analytics)) ~
      path("version")(version) ~
      pathPrefix("status")(status) ~
      (if (enableClientRelay)
         extractClientIP(_.toOption match {
           case None =>
             complete(
               (InternalServerError,
                "Could not extract remote address. Check " +
                  "akka.http.server.remote-address-header = on.")
             )

           case Some(remoteAddress) =>
             pathPrefix("zone")(
               authenticateSelfSignedJwt(
                 publicKey =>
                   zoneCommand(remoteAddress, publicKey) ~
                     zoneNotifications(remoteAddress, publicKey)
               )
             ) ~
               path("ws")(
                 handleWebSocketMessages(webSocketFlow(remoteAddress))
               )
         })
       else reject)

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
      claims <- JwtJson
        .decodeJson(
          token,
          JwtOptions(signature = false, expiration = false, notBefore = false)
        )
        .map(provide)
        .getOrElse(unauthorized("Token must be a JWT."))
      subject <- (claims \ "sub")
        .asOpt[String]
        .map(provide)
        .getOrElse(unauthorized("Token claims must contain a subject."))
      publicKey <- Try(
        KeyFactory
          .getInstance("RSA")
          .generatePublic(
            new X509EncodedKeySpec(
              okio.ByteString.decodeBase64(subject).toByteArray
            )
          )
      ).map(provide)
        .getOrElse(unauthorized("Token subject must be an RSA public key."))
      _ <- {
        if (JwtJson.isValid(token, publicKey, Seq(JwtAlgorithm.RS256)))
          provide(())
        else
          unauthorized[Unit](
            "Token must be signed by subject's private key " +
              "and used between nbf and iat claims.")
      }
    } yield PublicKey(publicKey.getEncoded)

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

  private[this] def analytics(implicit ec: ExecutionContext): Route =
    pathPrefix("zone" / zoneIdMatcher) { zoneId =>
      pathEnd(zone(zoneId)) ~
        path("balances")(balances(zoneId)) ~
        path("client-sessions")(clientSessions(zoneId))
    }

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
    path("terse")(
      get(
        complete(
          for (_ <- (getActiveClientSummaries,
                     getActiveZoneSummaries,
                     getZoneCount,
                     getPublicKeyCount,
                     getMemberCount,
                     getAccountCount,
                     getTransactionCount).tupled)
            yield "OK"
        )
      )
    ) ~ path("verbose")(
      get(
        complete(
          for ((activeClientSummaries,
                activeZoneSummaries,
                zoneCount,
                publicKeyCount,
                memberCount,
                accountCount,
                transactionCount) <- (getActiveClientSummaries,
                                      getActiveZoneSummaries,
                                      getZoneCount,
                                      getPublicKeyCount,
                                      getMemberCount,
                                      getAccountCount,
                                      getTransactionCount).tupled)
            yield
              Json.obj(
                "activeClients" -> Json.obj(
                  "count" -> activeClientSummaries.size,
                  "clients" -> activeClientSummaries
                    .groupBy(_.remoteAddress)
                    .toSeq
                    .sortBy {
                      case (remoteAddress, _) => remoteAddress.getHostAddress
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
                              case (publicKey, _) => publicKey.fingerprint
                            }
                            .map {
                              case (publicKey, clientsWithPublicKey) =>
                                Json.obj(
                                  "publicKeyFingerprint" -> publicKey.fingerprint,
                                  "count" -> clientsWithPublicKey.size,
                                  "clientsWithPublicKey" -> Json.obj(
                                    "count" -> clientsWithPublicKey.size,
                                    "connectionIds" -> clientsWithPublicKey
                                      .map(_.connectionId)
                                      .toSeq
                                      .sorted
                                  )
                                )
                            }
                        )
                    }
                ),
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
                          "clientConnections" -> Json.obj(
                            "count" -> connectedClients.size,
                            "publicKeyFingerprints" -> connectedClients
                              .map(_.fingerprint)
                              .toSeq
                              .sorted
                          )
                        )
                    }
                ),
                "totals" -> Json.obj(
                  "zones" -> zoneCount,
                  "publicKeys" -> publicKeyCount,
                  "members" -> memberCount,
                  "accounts" -> accountCount,
                  "transactions" -> transactionCount
                )
              )
        )
      ))

  private[this] def zoneCommand(
      remoteAddress: InetAddress,
      publicKey: PublicKey)(implicit ec: ExecutionContext): Route =
    put(
      pathEnd(
        entity(as[proto.ws.protocol.ZoneCommand.CreateZoneCommand]) {
          protoCreateZoneCommand =>
            val createZoneCommand =
              ProtoBinding[CreateZoneCommand,
                           proto.ws.protocol.ZoneCommand.CreateZoneCommand,
                           Any].asScala(
                protoCreateZoneCommand
              )(())
            complete(
              execCreateZoneCommand(remoteAddress, publicKey, createZoneCommand)
                .map(
                  zoneResponse =>
                    ProtoBinding[ZoneResponse,
                                 proto.ws.protocol.ZoneResponse,
                                 Any].asProto(
                      zoneResponse
                    )(()))
            )
        }
      ) ~
        path(zoneIdMatcher)(
          zoneId =>
            entity(as[proto.ws.protocol.ZoneCommand]) { protoZoneCommand =>
              val zoneCommand =
                ProtoBinding[ZoneCommand, proto.ws.protocol.ZoneCommand, Any]
                  .asScala(
                    protoZoneCommand
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
                                     Any].asProto(
                          zoneResponse
                        )(()))
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
                               Any].asProto(zoneNotification)(())
              )
        )
      )
    )

  private[this] def zone(zoneId: ZoneId)(implicit ec: ExecutionContext): Route =
    get(
      complete(
        getZone(zoneId).map(_.map(zone =>
          Json.parse(JsonFormat.toJsonString(
            ProtoBinding[Zone, proto.model.Zone, Any].asProto(zone)(())))))
      ))

  private[this] def balances(zoneId: ZoneId)(
      implicit ec: ExecutionContext): Route =
    get(
      complete(
        getBalances(zoneId).map(balances =>
          Json.obj(balances.map {
            case (accountId, balance) =>
              accountId.value.toString -> toJsFieldJsValueWrapper(balance)
          }.toSeq: _*))
      ))

  private[this] def clientSessions(zoneId: ZoneId)(
      implicit ec: ExecutionContext): Route =
    get(complete(
      getClientSessions(zoneId).map(
        clientSessions =>
          Json.obj(clientSessions.map {
            case (clientSessionId, clientSession) =>
              clientSessionId.value.toString -> toJsFieldJsValueWrapper(
                Json.obj(
                  "id" -> clientSession.id.value.toString,
                  "remoteAddress" -> clientSession.remoteAddress.map(
                    _.getHostAddress),
                  "actorRef" -> clientSession.actorRef,
                  "publicKeyFingerprint" -> clientSession.publicKey.fingerprint,
                  "joined" -> clientSession.joined,
                  "quit" -> clientSession.quit
                ))
          }.toSeq: _*)
      )
    ))

  protected[this] def isAdministrator(publicKey: PublicKey): Future[Boolean]

  protected[this] def akkaManagement: StandardRoute

  protected[this] def events(persistenceId: String,
                             fromSequenceNr: Long,
                             toSequenceNr: Long): Source[EventEnvelope, NotUsed]
  protected[this] def zoneState(
      zoneId: ZoneId): Future[proto.persistence.zone.ZoneState]

  protected[this] def getZone(zoneId: ZoneId): Future[Option[Zone]]
  protected[this] def getBalances(
      zoneId: ZoneId): Future[Map[AccountId, BigDecimal]]
  protected[this] def getClientSessions(
      zoneId: ZoneId): Future[Map[ClientSessionId, ClientSession]]

  protected[this] def getActiveClientSummaries: Future[Set[ActiveClientSummary]]
  protected[this] def getActiveZoneSummaries: Future[Set[ActiveZoneSummary]]
  protected[this] def getZoneCount: Future[Long]
  protected[this] def getPublicKeyCount: Future[Long]
  protected[this] def getMemberCount: Future[Long]
  protected[this] def getAccountCount: Future[Long]
  protected[this] def getTransactionCount: Future[Long]

  protected[this] def execCreateZoneCommand(
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
  protected[this] def webSocketFlow(
      remoteAddress: InetAddress): Flow[WsMessage, WsMessage, NotUsed]

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
        implicit ec =>
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
