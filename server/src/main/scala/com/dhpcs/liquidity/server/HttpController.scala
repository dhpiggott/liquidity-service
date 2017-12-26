package com.dhpcs.liquidity.server

import java.net.InetAddress

import akka.http.scaladsl.common.{
  EntityStreamingSupport,
  JsonEntityStreamingSupport
}
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.{Flow, Source}
import akka.NotUsed
import cats.instances.future._
import cats.syntax.apply._
import com.dhpcs.liquidity.actor.protocol.clientmonitor.ActiveClientSummary
import com.dhpcs.liquidity.actor.protocol.zonemonitor.ActiveZoneSummary
import com.dhpcs.liquidity.model.ProtoBindings._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.server.HttpController._
import com.trueaccord.scalapb.GeneratedMessage
import com.trueaccord.scalapb.json.JsonFormat
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport._
import play.api.libs.json.Json.toJsFieldJsValueWrapper
import play.api.libs.json.{JsValue, Json, OWrites, Writes}

import scala.concurrent.{ExecutionContext, Future}

object HttpController {

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

trait HttpController {

  protected[this] def httpRoutes(enableClientRelay: Boolean)(
      implicit ec: ExecutionContext): Route =
    version ~ diagnostics ~ (if (enableClientRelay) ws else reject) ~ status ~ analytics

  private[this] implicit val generatedMessageMarshaller
    : ToEntityMarshaller[GeneratedMessage] =
    PlayJsonSupport
      .marshaller[JsValue]
      .compose(entity => Json.parse(JsonFormat.toJsonString(entity)))

  private[this] implicit val jsonStreamingSupport: JsonEntityStreamingSupport =
    EntityStreamingSupport.json()

  private[this] implicit def optionResultMarshaller[A: Writes]
    : ToResponseMarshaller[Option[A]] =
    Marshaller[Option[A], HttpResponse](implicit ec => {
      case None =>
        Future.successful(List(Marshalling.Opaque(() =>
          HttpResponse(status = StatusCodes.NotFound))))
      case Some(entity) =>
        val marshaller = PlayJsonSupport
          .marshaller[A]
          .map(entity => HttpResponse(status = StatusCodes.OK, entity = entity))
        marshaller(entity)
    })

  private[this] def version: Route =
    path("version")(
      get(
        complete(
          Json.obj(
            BuildInfo.toMap
              .mapValues(value => toJsFieldJsValueWrapper(value.toString))
              .toSeq: _*
          ))))

  private[this] def diagnostics: Route =
    get(
      pathPrefix("diagnostics")(
        pathPrefix("events")(
          pathPrefix(Remaining)(persistenceId =>
            parameters(("fromSequenceNr".as[Long] ? 0L,
                        "toSequenceNr".as[Long] ? Long.MaxValue)) {
              (fromSequenceNr, toSequenceNr) =>
                complete(events(persistenceId, fromSequenceNr, toSequenceNr))
          })
        ) ~ pathPrefix("zones")(
          path(JavaUUID)(id => complete(zoneState(ZoneId(id.toString))))
        )
      ))

  private[this] def ws: Route =
    path("ws")(extractClientIP(_.toOption match {
      case None =>
        complete(
          (InternalServerError,
           "Could not extract remote address. Check " +
             "akka.http.server.remote-address-header = on.")
        )
      case Some(remoteAddress) =>
        handleWebSocketMessages(webSocketApi(remoteAddress))
    }))

  private[this] def status(implicit ec: ExecutionContext): Route =
    path("status")(
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
              ))))

  private[this] def analytics(implicit ec: ExecutionContext): Route =
    pathPrefix("analytics")(
      pathPrefix("zones")(pathPrefix(JavaUUID)(id =>
        pathEnd(zone(id.toString)) ~
          path("balances")(balances(id.toString)))))

  private[this] def zone(id: String)(implicit ec: ExecutionContext): Route =
    get(
      complete(
        getZone(ZoneId(id)).map(_.map(zone =>
          Json.parse(JsonFormat.toJsonString(
            ProtoBinding[Zone, proto.model.Zone, Any].asProto(zone)(())))))))

  private[this] def balances(id: String)(implicit ec: ExecutionContext): Route =
    get(complete(getBalances(ZoneId(id)).map(balances =>
      Json.obj(balances.map {
        case (accountId, balance) =>
          accountId.value.toString -> toJsFieldJsValueWrapper(balance)
      }.toSeq: _*))))

  protected[this] def events(persistenceId: String,
                             fromSequenceNr: Long,
                             toSequenceNr: Long): Source[EventEnvelope, NotUsed]
  protected[this] def zoneState(
      zoneId: ZoneId): Future[proto.persistence.zone.ZoneState]

  protected[this] def webSocketApi(
      remoteAddress: InetAddress): Flow[Message, Message, NotUsed]

  protected[this] def getActiveClientSummaries: Future[Set[ActiveClientSummary]]
  protected[this] def getActiveZoneSummaries: Future[Set[ActiveZoneSummary]]
  protected[this] def getZoneCount: Future[Long]
  protected[this] def getPublicKeyCount: Future[Long]
  protected[this] def getMemberCount: Future[Long]
  protected[this] def getAccountCount: Future[Long]
  protected[this] def getTransactionCount: Future[Long]
  protected[this] def getZone(zoneId: ZoneId): Future[Option[Zone]]
  protected[this] def getBalances(
      zoneId: ZoneId): Future[Map[AccountId, BigDecimal]]

}
