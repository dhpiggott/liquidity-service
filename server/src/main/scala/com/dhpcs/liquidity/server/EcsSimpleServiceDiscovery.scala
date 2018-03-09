package com.dhpcs.liquidity.server

import java.util.concurrent.TimeoutException

import akka.actor.ActorSystem
import akka.discovery.SimpleServiceDiscovery
import akka.discovery.SimpleServiceDiscovery.{Resolved, ResolvedTarget}
import akka.pattern.after
import com.amazonaws.regions.Regions
import com.amazonaws.services.ecs.model._
import com.amazonaws.services.ecs.{AmazonECS, AmazonECSClientBuilder}
import com.dhpcs.liquidity.server.EcsSimpleServiceDiscovery._

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class EcsSimpleServiceDiscovery(system: ActorSystem)
    extends SimpleServiceDiscovery {

  override def lookup(name: String,
                      resolveTimeout: FiniteDuration): Future[Resolved] = {
//    val ecsClient = AmazonECSClientBuilder.defaultClient()
    val ecsClient =
      AmazonECSClientBuilder.standard().withRegion(Regions.US_EAST_1).build()
    import system.dispatcher
    Future.firstCompletedOf(
      Seq(
        after(resolveTimeout, using = system.scheduler)(
          Future.failed(new TimeoutException("Future timed out!"))
        ),
        // TODO: Async client?
        Future {
          Resolved(
            serviceName = name,
            addresses = for {
              task <- resolveTasks(ecsClient, name)
              container <- task.getContainers.asScala
              networkInterface <- container.getNetworkInterfaces.asScala
            } yield
              ResolvedTarget(
                host = networkInterface.getPrivateIpv4Address,
                port = None
              )
          )
        }
      )
    )
  }
}

object EcsSimpleServiceDiscovery {

  def main(args: Array[String]): Unit =
    Await.result(
      awaitable = {
        val system = ActorSystem()
        implicit val ec: ExecutionContext = ExecutionContext.global
        for {
          resolved <- new EcsSimpleServiceDiscovery(system)
            .lookup(name = args(0), resolveTimeout = 5.seconds)
          _ = Console.out.println(resolved)
          _ <- system.terminate()
        } yield ()
      },
      atMost = 10.seconds
    )

  private def resolveTasks(ecsClient: AmazonECS,
                           serviceName: String): Seq[Task] =
    describeTasks(ecsClient, listTaskArns(ecsClient, serviceName))

  @tailrec private[this] def listTaskArns(
      ecsClient: AmazonECS,
      serviceName: String,
      pageTaken: Option[String] = None,
      accumulator: Seq[String] = Seq.empty): Seq[String] = {
    val listTasksResult = ecsClient.listTasks(
      new ListTasksRequest()
        .withServiceName(serviceName)
        .withNextToken(pageTaken.orNull)
        .withDesiredStatus(DesiredStatus.RUNNING)
    )
    val accumulatedTasksArns = accumulator ++ listTasksResult.getTaskArns.asScala
    listTasksResult.getNextToken match {
      case null =>
        accumulatedTasksArns

      case nextPageToken =>
        listTaskArns(
          ecsClient,
          serviceName,
          Some(nextPageToken),
          accumulatedTasksArns
        )
    }
  }

  private[this] def describeTasks(ecsClient: AmazonECS,
                                  taskArns: Seq[String]): Seq[Task] =
    for {
      // Each DescribeTasksRequest can contain at most 100 task ARNs.
      group <- taskArns.grouped(100).to[Seq]
      tasks = ecsClient.describeTasks(
        new DescribeTasksRequest()
          .withTasks(group.asJava)
      )
      task <- tasks.getTasks.asScala
    } yield task

}
