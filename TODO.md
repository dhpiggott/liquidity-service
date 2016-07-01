TODO
====

* Convert to using Akka HTTP directly (no Play framework) -- see
  http://doc.akka.io/docs/akka/2.4.3/intro/deployment-scenarios.html#deployment-scenarios
  and https://github.com/owainlewis/activator-akka-http
  * Then rewrite ApplicationSpec using WSProbe and remove akka-http-core test dependency
  * Then replace KeepAliveNotification with PongMessage
* ?Revert the ActorSuite change
* Add ClientConnectionSpec
* Review whether PassivationCountdown actor is still needed
* Add a healthcheck/post-deployment test
* Update to Cassandra 3.5
* Change backup strategy: https://docs.datastax.com/en/cassandra/2.1/cassandra/operations/ops_backup_restore_c.html
* Reinject sidelined 0.10 Zone, switch to a sensible serialization format:
  * https://github.com/dnvriend/akka-serialization-test
  * http://doc.akka.io/docs/akka/2.4.7/scala/persistence-schema-evolution.html
  * http://doc.akka.io/docs/akka/2.4.7/scala/persistence.html#event-adapters
  * http://doc.akka.io/docs/akka/2.4.7/scala/persistence.html#custom-serialization
  * http://doc.akka.io/docs/akka/2.4.7/scala/serialization.html
  * http://www.cakesolutions.net/teamblogs/using-spark-to-analyse-akka-persistence-events-in-cassandra
  * https://github.com/muvr/muvr-server/blob/develop/main/src/main/resources/main.conf
  * http://www.lagomframework.com/documentation/1.0.x/java/Serialization.html
  * https://github.com/akka/akka-persistence-cassandra/commit/f40e25110b62ce0f7d8b52fe6c76ce656fc5b74e
* Apply renaming (adding Actor suffixes)
* Enable snapshot store because as per http://doc.akka.io/docs/akka/2.4.3/scala/persistence.html: "Note that Cluster
  Sharding is using snapshots, so if you use Cluster Sharding you need to define a snapshot store plugin."
