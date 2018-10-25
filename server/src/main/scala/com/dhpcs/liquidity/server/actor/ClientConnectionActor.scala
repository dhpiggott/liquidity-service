package com.dhpcs.liquidity.server.actor

import java.net.InetAddress

import akka.NotUsed
import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.scaladsl.Source
import akka.stream.typed.scaladsl.ActorSource
import akka.stream.{Materializer, OverflowStrategy}
import com.dhpcs.liquidity.actor.protocol.clientconnection._
import com.dhpcs.liquidity.actor.protocol.zonevalidator._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.ws.protocol._

object ClientConnectionActor {

  sealed abstract class ActorSourceMessage
  final case class ForwardZoneNotification(zoneNotification: ZoneNotification)
      extends ActorSourceMessage
  case object StopActorSource extends ActorSourceMessage

  def zoneNotificationSource(
      zoneValidatorShardRegion: ActorRef[SerializableZoneValidatorMessage],
      remoteAddress: InetAddress,
      publicKey: PublicKey,
      zoneId: ZoneId,
      actorRefFactory: Behavior[ClientConnectionMessage] => ActorRef[
        ClientConnectionMessage]
  )(implicit mat: Materializer): Source[ZoneNotification, NotUsed] = {
    val (outActor, source) = ActorSource
      .actorRef[ActorSourceMessage](
        completionMatcher = {
          case StopActorSource => ()
        },
        failureMatcher = PartialFunction.empty,
        bufferSize = 16,
        overflowStrategy = OverflowStrategy.fail
      )
      .preMaterialize()
    actorRefFactory(
      ClientConnectionActor.zoneNotificationBehavior(
        zoneValidatorShardRegion,
        remoteAddress,
        publicKey,
        zoneId,
        outActor
      )
    )
    source.collect {
      case ForwardZoneNotification(zoneNotification) => zoneNotification
    }
  }

  private def zoneNotificationBehavior(
      zoneValidatorShardRegion: ActorRef[SerializableZoneValidatorMessage],
      remoteAddress: InetAddress,
      publicKey: PublicKey,
      zoneId: ZoneId,
      zoneNotificationOut: ActorRef[ActorSourceMessage])
    : Behavior[ClientConnectionMessage] =
    Behaviors.setup { context =>
      context.watchWith(zoneNotificationOut, ConnectionClosed)
      context.log.info(s"Starting for ${publicKey.fingerprint}@$remoteAddress")
      zoneValidatorShardRegion ! ZoneNotificationSubscription(
        context.self,
        zoneId,
        remoteAddress,
        publicKey
      )
      forwardingZoneNotifications(
        zoneValidatorShardRegion,
        remoteAddress,
        publicKey,
        zoneId,
        zoneNotificationOut,
        expectedSequenceNumber = 0
      )
    }

  private[this] def forwardingZoneNotifications(
      zoneValidatorShardRegion: ActorRef[SerializableZoneValidatorMessage],
      remoteAddress: InetAddress,
      publicKey: PublicKey,
      zoneId: ZoneId,
      zoneNotificationOut: ActorRef[ActorSourceMessage],
      expectedSequenceNumber: Long): Behavior[ClientConnectionMessage] =
    Behaviors.receive[ClientConnectionMessage]((context, message) =>
      message match {
        case ZoneNotificationEnvelope(zoneValidator,
                                      _,
                                      sequenceNumber,
                                      zoneNotification) =>
          if (sequenceNumber != expectedSequenceNumber) {
            context.log.warning(
              s"Rejoining for ${publicKey.fingerprint}@$remoteAddress due " +
                "to unexpected sequence number " +
                s"($sequenceNumber != $expectedSequenceNumber)"
            )
            zoneValidatorShardRegion ! ZoneNotificationSubscription(
              context.self,
              zoneId,
              remoteAddress,
              publicKey
            )
            forwardingZoneNotifications(
              zoneValidatorShardRegion,
              remoteAddress,
              publicKey,
              zoneId,
              zoneNotificationOut,
              expectedSequenceNumber = 0
            )
          } else {
            zoneNotification match {
              case ZoneStateNotification(None, _) =>
                context.log.warning(
                  s"Stopping for ${publicKey.fingerprint}@$remoteAddress " +
                    "because zone does not exist")
                Behaviors.stopped

              case _ =>
                zoneNotification match {
                  case ZoneStateNotification(Some(_), _) =>
                    context.watchWith(zoneValidator, ZoneTerminated)

                  case _ =>
                    ()
                }
                zoneNotificationOut ! ForwardZoneNotification(zoneNotification)
                forwardingZoneNotifications(
                  zoneValidatorShardRegion,
                  remoteAddress,
                  publicKey,
                  zoneId,
                  zoneNotificationOut,
                  expectedSequenceNumber = sequenceNumber + 1
                )
            }
          }

        case ConnectionClosed =>
          context.log.info(
            s"Stopping for ${publicKey.fingerprint}@$remoteAddress")
          Behaviors.stopped

        case ZoneTerminated =>
          context.log.warning(
            s"Stopping for ${publicKey.fingerprint}@$remoteAddress due to " +
              "zone termination")
          Behaviors.stopped
    }) receiveSignal {
      case (_, PostStop) =>
        zoneNotificationOut ! StopActorSource
        Behaviors.same
    }

}
