package com.dhpcs.liquidity.server

import java.net.InetAddress
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec

import akka.NotUsed
import akka.http.scaladsl.common._
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.stream.scaladsl.{Flow, Source}
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
import com.trueaccord.scalapb.GeneratedMessage
import com.trueaccord.scalapb.json.JsonFormat
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport._
import pdi.jwt.{JwtAlgorithm, JwtJson, JwtOptions}
import play.api.libs.json.Json.toJsFieldJsValueWrapper
import play.api.libs.json.{JsValue, Json, OWrites}

import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

trait HttpController {

  protected[this] def httpRoutes(enableClientRelay: Boolean)(
      implicit ec: ExecutionContext): Route =
    pathPrefix("akka-management")(administratorRealm(akkaManagement)) ~
      pathPrefix("diagnostics")(administratorRealm(diagnostics)) ~
      pathPrefix("analytics")(administratorRealm(analytics)) ~
      path("version")(version) ~
      path("status")(status) ~
      (if (enableClientRelay) path("ws")(ws) else reject)

  private[this] def administratorRealm: Directive0 =
    for {
      publicKey <- authenticateSelfSignedToken
      _ <- authorizeByPublicKey(publicKey)
    } yield ()

  private[this] def authenticateSelfSignedToken: Directive1[PublicKey] =
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

  private[this] def unauthorized[A](error: String): Directive1[A] =
    complete(
      (
        Unauthorized,
        Seq(
          `WWW-Authenticate`(
            HttpChallenges
              .oAuth2(realm = "Administration")
              .copy(params = Map("error" -> error))
          )
        )
      )
    )

  private[this] def forbidden[A]: Directive1[A] =
    complete(Forbidden)

  private[this] implicit def generatedMessageEntityMarshaller(
      implicit jsValueEntityMarshaller: ToEntityMarshaller[JsValue])
    : ToEntityMarshaller[GeneratedMessage] =
    jsValueEntityMarshaller
      .compose[String](Json.parse)
      .compose[GeneratedMessage](JsonFormat.toJsonString)

  private[this] implicit val jsonStreamingSupport: JsonEntityStreamingSupport =
    EntityStreamingSupport.json()

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
                "publicKeyFingerprints" -> activeClientSummaries
                  .map(_.publicKey.fingerprint)
                  .toSeq
                  .sorted
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
                                           clientConnections) =>
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
                          "count" -> clientConnections.size,
                          "publicKeyFingerprints" -> clientConnections
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
    )

  private[this] def ws: Route =
    extractClientIP(_.toOption match {
      case None =>
        complete(
          (InternalServerError,
           "Could not extract remote address. Check " +
             "akka.http.server.remote-address-header = on.")
        )
      case Some(remoteAddress) =>
        handleWebSocketMessages(webSocketApi(remoteAddress))
    })

  private[this] implicit def optionResponseMarshaller[A](
      implicit entityMarshaller: ToEntityMarshaller[Option[A]])
    : ToResponseMarshaller[Option[A]] =
    Marshaller[Option[A], HttpResponse](
      implicit ec =>
        entity =>
          Marshaller.fromToEntityMarshaller(status =
            if (entity.isEmpty) NotFound else OK)(entityMarshaller)(entity)
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

  protected[this] def webSocketApi(
      remoteAddress: InetAddress): Flow[Message, Message, NotUsed]

}

object HttpController {

  private val zoneIdMatcher = JavaUUID.map(uuid => ZoneId(uuid.toString))

  final case class EventEnvelope(sequenceNr: Long, event: GeneratedMessage)

  object EventEnvelope {
    implicit final val GeneratedMessageEnvelopeWrites: OWrites[EventEnvelope] =
      OWrites(
        GeneratedMessageEnvelope =>
          Json.obj(
            "sequenceNr" -> GeneratedMessageEnvelope.sequenceNr,
            // ScalaPB gives us a way to get a json4s representation of
            // messages, but not play-json. So we parse the string form as a
            // play-json JsValue (doing this, as opposed to just passing it
            // through as a string means that it will get marshaled by
            // akka-http-play-json, which by default will _pretty_ print it -
            // and we really want that, given this is purely a diagnostics
            // endpoint and should thus be developer-readable).
            "event" -> Json.parse(
              JsonFormat.toJsonString(GeneratedMessageEnvelope.event))
        ))
  }
}
