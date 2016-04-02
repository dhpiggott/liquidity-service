TODO
====

* Rewrite ApplicationSpec using WSProbe(?), remove akka-http-core test dependency
* Review whether PassivationCountdown actor is still needed
* Make test styles consistent -- use WordSpec(Like) -- review akka persistence cassandra for example (https://github.com/akka/akka-persistence-cassandra/blob/master/src/test/scala/akka/persistence/cassandra/journal/CassandraIntegrationSpec.scala)
* Reinject sidelined 0.10 Zone, switch to a sensible serialization format:
  * http://doc.akka.io/docs/akka/2.4.2/scala/persistence-schema-evolution.html
  * http://doc.akka.io/docs/akka/2.4.2/scala/persistence.html#event-adapters
  * http://doc.akka.io/docs/akka/2.4.2/scala/persistence.html#custom-serialization
  * http://doc.akka.io/docs/akka/2.4.2/scala/serialization.html
  * http://www.cakesolutions.net/teamblogs/using-spark-to-analyse-akka-persistence-events-in-cassandra
  * https://github.com/muvr/muvr-server/blob/develop/main/src/main/resources/main.conf
  * http://www.lagomframework.com/documentation/1.0.x/Serialization.html
  * https://github.com/akka/akka-persistence-cassandra/commit/f40e25110b62ce0f7d8b52fe6c76ce656fc5b74e
* Change backup strategy: https://docs.datastax.com/en/cassandra/2.1/cassandra/operations/ops_backup_restore_c.html
* Update play-json-rpc to play-json 2.5.1, update README, remove play-json dependency override
* Release play-json-rpc
* Apply Clean Code chapter 5 to liquidity-tools, liquidity-server and liquidity
