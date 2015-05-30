name := "liquidity-server"

version := "1.0-SNAPSHOT"

dockerBaseImage := "java:jre"

dockerExposedPorts := Seq(80)

daemonUser in Docker := "root"

lazy val root = (project in file(".")).enablePlugins(PlayScala, DockerPlugin)

scalaVersion := "2.11.6"

libraryDependencies ++= Seq(
  ws,
  "com.dhpcs" %% "liquidity-common" % "0.5.0"
)

javaOptions in Universal ++= Seq(
  "-Dpidfile.path=/dev/null",
  "-Dhttp.port=80"
)
