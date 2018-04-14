package com.dhpcs.liquidity.server.actor

import java.net.InetAddress

import akka.NotUsed
import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Publish
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.typed.scaladsl.ActorSource
import akka.stream.{Materializer, OverflowStrategy}
import com.dhpcs.liquidity.actor.protocol.clientconnection._
import com.dhpcs.liquidity.actor.protocol.clientmonitor._
import com.dhpcs.liquidity.actor.protocol.zonevalidator._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.ws.protocol._

import scala.concurrent.duration._

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
    val (outActor, publisher) = ActorSource
      .actorRef[ActorSourceMessage](
        completionMatcher = {
          case StopActorSource => ()
        },
        failureMatcher = PartialFunction.empty,
        bufferSize = 16,
        overflowStrategy = OverflowStrategy.fail
      )
      .toMat(Sink.asPublisher(false))(Keep.both)
      .run()
    actorRefFactory(
      ClientConnectionActor.zoneNotificationBehavior(
        zoneValidatorShardRegion,
        remoteAddress,
        publicKey,
        zoneId,
        outActor
      )
    )
    Source.fromPublisher(publisher).collect {
      case ForwardZoneNotification(zoneNotification) => zoneNotification
    }
  }

  private[this] case object PublishStatusTimerKey

  private def zoneNotificationBehavior(
      zoneValidatorShardRegion: ActorRef[SerializableZoneValidatorMessage],
      remoteAddress: InetAddress,
      publicKey: PublicKey,
      zoneId: ZoneId,
      zoneNotificationOut: ActorRef[ActorSourceMessage])
    : Behavior[ClientConnectionMessage] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        context.watchWith(zoneNotificationOut, ConnectionClosed)
        context.log.info(
          s"Starting for ${publicKey.fingerprint}@$remoteAddress")
        val mediator = DistributedPubSub(context.system.toUntyped).mediator
        context.self ! PublishClientStatusTick
        timers.startPeriodicTimer(PublishStatusTimerKey,
                                  PublishClientStatusTick,
                                  30.seconds)
        zoneValidatorShardRegion ! ZoneNotificationSubscription(
          context.self,
          zoneId,
          remoteAddress,
          publicKey
        )
        implicit val resolver: ActorRefResolver =
          ActorRefResolver(context.system)
        forwardingZoneNotifications(
          zoneValidatorShardRegion,
          remoteAddress,
          publicKey,
          zoneId,
          zoneNotificationOut,
          mediator,
          expectedSequenceNumber = 0
        )
      }
    }

  private[this] def forwardingZoneNotifications(
      zoneValidatorShardRegion: ActorRef[SerializableZoneValidatorMessage],
      remoteAddress: InetAddress,
      publicKey: PublicKey,
      zoneId: ZoneId,
      zoneNotificationOut: ActorRef[ActorSourceMessage],
      mediator: ActorRef[Publish],
      expectedSequenceNumber: Long)(
      implicit resolver: ActorRefResolver): Behavior[ClientConnectionMessage] =
    Behaviors.receive[ClientConnectionMessage]((context, message) =>
      message match {
        case PublishClientStatusTick =>
          mediator ! Publish(
            ClientMonitorActor.ClientStatusTopic,
            UpsertActiveClientSummary(
              context.self,
              ActiveClientSummary(remoteAddress,
                                  publicKey,
                                  resolver.toSerializationFormat(context.self))
            )
          )
          Behaviors.same

        case ZoneNotificationEnvelope(zoneValidator,
                                      _,
                                      sequenceNumber,
                                      zoneNotification) =>
          if (sequenceNumber != expectedSequenceNumber) {
            context.log.warning(
              "Rejoining due to unexpected sequence number " +
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
              mediator,
              expectedSequenceNumber = 0
            )
          } else {
            zoneNotification match {
              case ZoneStateNotification(None, _) =>
                context.log.warning("Stopping because zone does not exist")
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
                  mediator,
                  expectedSequenceNumber = sequenceNumber + 1
                )
            }
          }

        case ConnectionClosed =>
          Behaviors.stopped

        case ZoneTerminated =>
          context.log.warning("Stopping due to zone termination")
          Behaviors.stopped
    }) receiveSignal {
      case (context, PostStop) =>
        zoneNotificationOut ! StopActorSource
        context.log.info(s"Stopped for ${publicKey.fingerprint}@$remoteAddress")
        Behaviors.same
    }

}
