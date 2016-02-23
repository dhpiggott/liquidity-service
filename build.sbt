name := "liquidity-server"

version := "1.0-SNAPSHOT"

scalaVersion := "2.11.7"

dockerBaseImage := "java:8-jre"

dockerExposedPorts := Seq(80)

daemonUser in Docker := "root"

lazy val root = (project in file(".")).enablePlugins(PlayScala, DockerPlugin)

libraryDependencies ++= Seq(
  "com.dhpcs" %% "liquidity-common" % "1.0.0",
  "com.typesafe.akka" %% "akka-actor" % "2.4.2",
  "com.typesafe.akka" %% "akka-cluster" % "2.4.2",
  "com.typesafe.akka" %% "akka-cluster-sharding" % "2.4.2",
  "com.typesafe.akka" %% "akka-cluster-tools" % "2.4.2",
  "com.typesafe.akka" %% "akka-persistence" % "2.4.2",
  "com.github.krasserm" %% "akka-persistence-cassandra" % "0.6"
)

resolvers ++= Seq(
  "krasserm at bintray" at "https://dl.bintray.com/krasserm/maven"
)

javaOptions in Universal ++= Seq(
  "-Dpidfile.path=/dev/null",
  "-Dhttp.port=80"
)

routesGenerator := InjectedRoutesGenerator
