package com.dhpcs.liquidity.server

import akka.actor.ActorSystem
import akka.discovery.SimpleServiceDiscovery
import akka.discovery.SimpleServiceDiscovery.{Resolved, ResolvedTarget}

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.Future

class HardcodedSimpleServiceDiscovery(system: ActorSystem)
    extends SimpleServiceDiscovery {

  private[this] val hostnames = system.settings.config
    .getStringList("akka.discovery.hardcoded.hostnames")
    .asScala
    .to[Seq]

  override def lookup(name: String,
                      resolveTimeout: FiniteDuration): Future[Resolved] =
    Future.successful(
      Resolved(
        serviceName = name,
        addresses = for (hostname <- hostnames)
          yield
            ResolvedTarget(
              host = hostname,
              port = None
            )
      )
    )

}
