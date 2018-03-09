package com.dhpcs.liquidity.server

import java.util.concurrent.TimeoutException

import akka.actor.ActorSystem
import akka.discovery.SimpleServiceDiscovery
import akka.discovery.SimpleServiceDiscovery.{Resolved, ResolvedTarget}
import akka.pattern.after
import com.dhpcs.liquidity.server.EcsSimpleServiceDiscovery._
import software.amazon.awssdk.core.regions.Region
import software.amazon.awssdk.services.ecs._
import software.amazon.awssdk.services.ecs.model._

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.compat.java8.FutureConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class EcsSimpleServiceDiscovery(system: ActorSystem)
    extends SimpleServiceDiscovery {

  override def lookup(name: String,
                      resolveTimeout: FiniteDuration): Future[Resolved] = {
//    val ecsClient = ECSAsyncClient.create()
    val ecsClient =
      ECSAsyncClient.builder().region(Region.US_EAST_1).build()
    implicit val ec: ExecutionContext = system.dispatcher
    Future.firstCompletedOf(
      Seq(
        after(resolveTimeout, using = system.scheduler)(
          Future.failed(new TimeoutException("Future timed out!"))
        ),
        resolveTasks(ecsClient, name).map(
          tasks =>
            Resolved(
              serviceName = name,
              addresses = for {
                task <- tasks
                container <- task.containers().asScala
                networkInterface <- container.networkInterfaces().asScala
              } yield
                ResolvedTarget(
                  host = networkInterface.privateIpv4Address(),
                  port = None
                )
          )
        )
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

  private def resolveTasks(ecsClient: ECSAsyncClient, serviceName: String)(
      implicit ec: ExecutionContext): Future[Seq[Task]] =
    for {
      taskArns <- listTaskArns(ecsClient, serviceName)
      task <- describeTasks(ecsClient, taskArns)
    } yield task

  private[this] def listTaskArns(ecsClient: ECSAsyncClient,
                                 serviceName: String,
                                 pageTaken: Option[String] = None,
                                 accumulator: Seq[String] = Seq.empty)(
      implicit ec: ExecutionContext): Future[Seq[String]] =
    for {
      listTasksResponse <- toScala(
        ecsClient.listTasks(
          ListTasksRequest
            .builder()
            .serviceName(serviceName)
            .nextToken(pageTaken.orNull)
            .desiredStatus(DesiredStatus.RUNNING)
            .build()
        )
      )
      accumulatedTasksArns = accumulator ++ listTasksResponse.taskArns().asScala
      taskArns <- listTasksResponse.nextToken() match {
        case null =>
          Future.successful(accumulatedTasksArns)

        case nextPageToken =>
          listTaskArns(
            ecsClient,
            serviceName,
            Some(nextPageToken),
            accumulatedTasksArns
          )
      }
    } yield taskArns

  private[this] def describeTasks(
      ecsClient: ECSAsyncClient,
      taskArns: Seq[String])(implicit ec: ExecutionContext): Future[Seq[Task]] =
    for {
      // Each DescribeTasksRequest can contain at most 100 task ARNs.
      describeTasksResponses <- Future
        .traverse(taskArns.grouped(100))(
          taskArnGroup =>
            toScala(
              ecsClient.describeTasks(
                DescribeTasksRequest
                  .builder()
                  .tasks(taskArnGroup.asJava)
                  .build()
              )
          )
        )
      tasks = describeTasksResponses.flatMap(_.tasks().asScala).to[Seq]
    } yield tasks

}
