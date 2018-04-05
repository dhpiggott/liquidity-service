lazy val `ws-protocol` = project
  .in(file("ws-protocol"))
  .settings(
    name := "liquidity-ws-protocol"
  )
  .disablePlugins(ScalafmtCorePlugin)
  .settings(
    libraryDependencies := Seq.empty,
    coverageEnabled := false,
    crossPaths := false,
    Compile / unmanagedResourceDirectories ++=
      (Compile / PB.protoSources).value.map(_.asFile),
    homepage := Some(url("https://github.com/dhpcs/liquidity/")),
    startYear := Some(2015),
    description := "Virtual currencies for Monopoly and other board and " +
      "tabletop games.",
    licenses += "Apache-2.0" -> url(
      "https://www.apache.org/licenses/LICENSE-2.0.txt"),
    organization := "com.dhpcs",
    organizationHomepage := Some(url("https://www.dhpcs.com/")),
    organizationName := "dhpcs",
    developers := List(
      Developer(
        id = "dhpiggott",
        name = "David Piggott",
        email = "david@piggott.me.uk",
        url = url("https://www.dhpiggott.net/")
      )),
    scmInfo := Some(
      ScmInfo(
        browseUrl = url("https://github.com/dhpcs/liquidity/"),
        connection = "scm:git:https://github.com/dhpcs/liquidity.git",
        devConnection = Some("scm:git:git@github.com:dhpcs/liquidity.git")
      )),
    releaseEarlyEnableInstantReleases := false,
    releaseEarlyNoGpg := true,
    releaseEarlyWith := BintrayPublisher,
    releaseEarlyEnableSyncToMaven := false,
    bintrayOrganization := Some("dhpcs")
  )

lazy val `ws-protocol-scala-binding` = project
  .in(file("ws-protocol-scala-binding"))
  .settings(
    name := "liquidity-ws-protocol-scala-binding"
  )
  .settings(evergreenVersionSettings)
  .settings(protobufScalaSettings(`ws-protocol`))
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % "1.1.0",
      "com.squareup.okio" % "okio" % "1.14.0"
    ))

lazy val `actor-protocol` = project
  .in(file("actor-protocol"))
  .settings(
    name := "liquidity-actor-protocol"
  )
  .settings(evergreenVersionSettings)
  .dependsOn(`ws-protocol`)
  .disablePlugins(ScalafmtCorePlugin)

lazy val `actor-protocol-scala-binding` = project
  .in(file("actor-protocol-scala-binding"))
  .settings(
    name := "liquidity-actor-protocol-scala-binding"
  )
  .settings(evergreenVersionSettings)
  .settings(protobufScalaSettings(`actor-protocol`))
  .settings(
    libraryDependencies += "com.typesafe.akka" %% "akka-actor-typed" % "2.5.11"
  )
  .dependsOn(`ws-protocol-scala-binding`)
  .settings(
    Compile / PB.includePaths += file("ws-protocol/src/main/protobuf")
  )

lazy val `proto-bindings` = project
  .in(file("proto-bindings"))
  .settings(
    name := "proto-bindings"
  )
  .dependsOn(`ws-protocol`)
  .dependsOn(`ws-protocol-scala-binding`)
  .dependsOn(`actor-protocol`)
  .dependsOn(`actor-protocol-scala-binding`)
  .settings(
    libraryDependencies += "com.chuusai" %% "shapeless" % "2.3.3"
  )

lazy val server = project
  .in(file("server"))
  .settings(
    name := "server"
  )
  .settings(evergreenVersionSettings)
  .dependsOn(`proto-bindings`)
  .settings(
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-slf4j" % "2.5.11",
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "com.lightbend.akka.management" %% "akka-management-cluster-http" % "0.10.0",
      "software.amazon.awssdk" % "ecs" % "2.0.0-preview-9",
      "org.scala-lang.modules" %% "scala-java8-compat" % "0.8.0",
      "com.lightbend.akka.discovery" %% "akka-discovery" % "0.10.0",
      "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % "0.10.0",
      "com.lightbend.akka.management" %% "akka-management-cluster-http" % "0.10.0",
      "com.typesafe.akka" %% "akka-cluster-typed" % "2.5.11",
      "com.typesafe.akka" %% "akka-cluster-sharding-typed" % "2.5.11",
      "com.typesafe.akka" %% "akka-cluster-tools" % "2.5.11",
      "com.typesafe.akka" %% "akka-persistence-typed" % "2.5.11",
      "com.typesafe.akka" %% "akka-persistence-query" % "2.5.11",
      "com.github.dnvriend" %% "akka-persistence-jdbc" % "3.3.0",
      "org.tpolecat" %% "doobie-core" % "0.5.2",
      "org.tpolecat" %% "doobie-hikari" % "0.5.2",
      "mysql" % "mysql-connector-java" % "5.1.46",
      "com.typesafe.akka" %% "akka-http" % "10.1.1",
      "com.typesafe.akka" %% "akka-stream-typed" % "2.5.11",
      "com.thesamet.scalapb" %% "scalapb-json4s" % "0.7.0",
      "com.typesafe.play" %% "play-json" % "2.6.9",
      "de.heikoseeberger" %% "akka-http-play-json" % "1.20.1",
      "com.pauldijou" %% "jwt-play-json" % "0.16.0"
    )
  )
  .settings(libraryDependencies ++= Seq(
    "com.h2database" % "h2" % "1.4.197" % Test,
    "com.typesafe.akka" %% "akka-testkit-typed" % "2.5.11" % Test,
    "com.github.dnvriend" %% "akka-persistence-inmemory" % "2.5.1.1" % Test,
    "org.scalatest" %% "scalatest" % "3.0.5" % Test,
    "com.typesafe.akka" %% "akka-http-testkit" % "10.1.1" % Test,
    "com.typesafe.akka" %% "akka-stream-testkit" % "2.5.11" % Test
  ))
  .configs(IntegrationTest)
  .settings(Defaults.itSettings)
  .settings(inConfig(IntegrationTest)(scalafmtSettings))
  .settings(libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.0.5" % IntegrationTest,
    "com.typesafe.akka" %% "akka-http-testkit" % "10.1.1" % IntegrationTest,
    "com.typesafe.akka" %% "akka-stream-testkit" % "2.5.11" % IntegrationTest
  ))
  .enablePlugins(BuildInfoPlugin, JavaAppPackaging, DockerPlugin)
  .settings(
    buildInfoPackage := "com.dhpcs.liquidity.server",
    buildInfoUsePackageAsPath := true,
    buildInfoKeys := Seq(version),
    buildInfoOptions ++= Seq(BuildInfoOption.BuildTime, BuildInfoOption.ToMap),
    Docker / packageName := "liquidity",
    dockerBaseImage := "openjdk:10-jre-slim"
  )

lazy val `client-simulation` = project
  .in(file("client-simulation"))
  .settings(
    name := "client-simulation"
  )
  .enablePlugins(GatlingPlugin)
  .dependsOn(`proto-bindings`)
  .settings(
    libraryDependencies ++= Seq(
      "io.gatling" % "gatling-test-framework" % "2.3.0" % Test,
      "io.gatling.highcharts" % "gatling-charts-highcharts" % "2.3.0" % Test,
      "com.pauldijou" %% "jwt-play-json" % "0.16.0" % Test
    )
  )

lazy val evergreenVersionSettings = Seq(
  version := {
    import scala.sys.process._
    s"evergreen-${"git describe --always --dirty".!!.trim()}"
  }
)

def protobufScalaSettings(project: Project) = Seq(
  Compile / PB.protoSources ++= (project / Compile / PB.protoSources).value,
  Compile / PB.targets := Seq(
    scalapb.gen(
      flatPackage = true,
      javaConversions = false,
      grpc = false,
      singleLineToProtoString = true
    ) -> (Compile / sourceManaged).value
  ),
  libraryDependencies += "com.thesamet.scalapb" %% "scalapb-runtime" %
    scalapb.compiler.Version.scalapbVersion % ProtocPlugin.ProtobufConfig
)
