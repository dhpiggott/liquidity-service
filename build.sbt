name := """liquidity"""

version := "1.0-SNAPSHOT"

dockerBaseImage := "java:jre"

dockerExposedPorts := Seq(80)

daemonUser in Docker := "root"

lazy val root = (project in file(".")).enablePlugins(PlayScala, DockerPlugin)

scalaVersion := "2.11.1"

libraryDependencies ++= Seq(
  cache,
  ws
)

javaOptions in Universal ++= Seq(
  "-Dpidfile.path=/dev/null",
  "-Dhttp.port=80"
)

resolvers += "Pellucid Bintray" at "http://dl.bintray.com/pellucid/maven"

libraryDependencies += "com.pellucid" %% "sealerate" % "0.0.3"