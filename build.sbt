name := """liquidity-server"""

version := "1.0-SNAPSHOT"

dockerBaseImage := "java:8-jre"

dockerExposedPorts := Seq(80)

daemonUser in Docker := "root"

lazy val root = (project in file(".")).enablePlugins(PlayScala, DockerPlugin)

scalaVersion := "2.11.7"

libraryDependencies ++= Seq(
  "com.dhpcs" %% "liquidity-common" % "0.34.0",
  "com.typesafe.akka" %% "akka-cluster" % "2.3.13",
  "com.typesafe.akka" %% "akka-contrib" % "2.3.13",
  "com.typesafe.akka" %% "akka-persistence-experimental" % "2.3.13",
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
