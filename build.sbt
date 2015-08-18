name := """liquidity-server"""

version := "1.0-SNAPSHOT"

dockerBaseImage := "java:8-jre"

dockerExposedPorts := Seq(80)

daemonUser in Docker := "root"

lazy val root = (project in file(".")).enablePlugins(PlayScala, DockerPlugin)

scalaVersion := "2.11.7"

libraryDependencies ++= Seq(
  "com.dhpcs" %% "liquidity-common" % "0.29.0",
  "com.typesafe.akka" %% "akka-cluster" % "2.3.12",
  // TODO:
  //
  // (None of this is a concern yet as we only have one node).
  //
  // When akka 2.4.0 core is released (which includes cluster sharding), be sure to enable auto-restart of downed shard
  // entities (it's not supported in the 2.3.12 contrib version). This will ensure that ClientConnections find out (via
  // the the ZoneStarting event sent in preStart by the new ZoneValidator instance) about downed ZoneValidator's and
  // thus know to rejoin the newly created one. It will also necessitate enabling auto-down, which in turn will require
  // thinking about partition handling (set a required quorum?).
  //
  // Also to-do at this time: review use of preStart/postStop broadcasts?
  "com.typesafe.akka" %% "akka-contrib" % "2.3.12",
  "com.typesafe.akka" %% "akka-persistence-experimental" % "2.3.12",
  "com.github.krasserm" %% "akka-persistence-cassandra" % "0.3.9",
  specs2 % Test
)

resolvers ++= Seq(
  "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases",
  "krasserm at bintray" at "http://dl.bintray.com/krasserm/maven"
)

javaOptions in Universal ++= Seq(
  "-Dpidfile.path=/dev/null",
  "-Dhttp.port=80"
)

routesGenerator := InjectedRoutesGenerator
