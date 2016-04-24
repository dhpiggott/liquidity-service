name := "liquidity-server"

version := "1.0-SNAPSHOT"

scalaVersion := "2.11.8"

dockerBaseImage := "java:8-jre"

dockerExposedPorts := Seq(80)

daemonUser in Docker := "root"

lazy val root = (project in file(".")).enablePlugins(PlayScala, DockerPlugin)

libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-json" % "2.4.6" force(),
  "com.dhpcs" %% "liquidity-common" % "1.0.0",
  "com.typesafe.akka" %% "akka-cluster-sharding" % "2.4.4",
  "com.typesafe.akka" %% "akka-persistence-cassandra" % "0.9",
  "com.typesafe.akka" %% "akka-persistence-query-experimental" % "2.4.4",
  "org.scalatest" %% "scalatest" % "2.2.6" % "test",
  "com.typesafe.akka" %% "akka-testkit" % "2.4.4" % "test",
  "org.scalatestplus.play" %% "scalatestplus-play" % "1.5.1" % "test",
  "com.typesafe.akka" %% "akka-stream-testkit" % "2.4.4" % "test",
  "com.typesafe.akka" %% "akka-http-experimental" % "2.4.4" % "test"
)

javaOptions in Test += "-Dconfig.resource=test.conf"

javaOptions in Universal ++= Seq(
  "-Dpidfile.path=/dev/null",
  "-Dhttp.port=80"
)
