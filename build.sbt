import org.scalafmt.sbt.ScalafmtPlugin

lazy val `ws-protocol` = project
  .in(file("ws-protocol"))
  .settings(
    name := "liquidity-ws-protocol"
  )
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
      )
    ),
    scmInfo := Some(
      ScmInfo(
        browseUrl = url("https://github.com/dhpcs/liquidity/"),
        connection = "scm:git:https://github.com/dhpcs/liquidity.git",
        devConnection = Some("scm:git:git@github.com:dhpcs/liquidity.git")
      )
    ),
    releaseEarlyEnableInstantReleases := false,
    releaseEarlyNoGpg := true,
    releaseEarlyWith := BintrayPublisher,
    releaseEarlyEnableSyncToMaven := false,
    bintrayOrganization := Some("dhpcs")
  )

lazy val model = project
  .in(file("model"))
  .settings(
    Compile / PB.protoSources ++= (`ws-protocol` / Compile / PB.protoSources).value,
    Compile / PB.targets := Seq(
      scalapb.gen(
        flatPackage = true,
        javaConversions = false,
        grpc = false,
        singleLineToProtoString = true
      ) -> (Compile / sourceManaged).value
    )
  )
  .settings(libraryDependencies ++= Seq(
    "com.thesamet.scalapb" %% "scalapb-runtime" %
      scalapb.compiler.Version.scalapbVersion % ProtocPlugin.ProtobufConfig,
    "org.typelevel" %% "cats-core" % "1.6.0",
    "com.squareup.okio" % "okio" % "2.2.2",
    "com.typesafe.akka" %% "akka-actor-typed" % "2.5.22"
  ))

lazy val service = project
  .in(file("service"))
  .dependsOn(model)
  .settings(
    libraryDependencies ++= Seq(
      "com.chuusai" %% "shapeless" % "2.3.3",
      "com.typesafe.akka" %% "akka-slf4j" % "2.5.22",
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "net.logstash.logback" % "logstash-logback-encoder" % "5.3",
      "com.typesafe.akka" %% "akka-discovery" % "2.5.22",
      "com.typesafe.akka" %% "akka-cluster-typed" % "2.5.22",
      "com.typesafe.akka" %% "akka-cluster-sharding-typed" % "2.5.22",
      "com.typesafe.akka" %% "akka-persistence-typed" % "2.5.22",
      "com.typesafe.akka" %% "akka-persistence-query" % "2.5.22",
      "com.lightbend.akka.discovery" %% "akka-discovery-aws-api-async" % "1.0.0",
      "com.lightbend.akka.management" %% "akka-management-cluster-http" % "1.0.0",
      "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % "1.0.0",
      "com.github.dnvriend" %% "akka-persistence-jdbc" % "3.5.0",
      "org.tpolecat" %% "doobie-core" % "0.6.0",
      "org.tpolecat" %% "doobie-hikari" % "0.6.0",
      "mysql" % "mysql-connector-java" % "8.0.16",
      "org.typelevel" %% "cats-effect" % "1.3.0",
      "org.scalaz" %% "scalaz-zio" % "1.0-RC4",
      "org.scalaz" %% "scalaz-zio-interop-cats" % "1.0-RC4",
      "com.typesafe.akka" %% "akka-http" % "10.1.8",
      "com.lightbend.akka" %% "akka-stream-alpakka-s3" % "1.0.0",
      "org.bouncycastle" % "bcpkix-jdk15on" % "1.61",
      "com.typesafe.akka" %% "akka-http2-support" % "10.1.8",
      "com.typesafe.akka" %% "akka-http-spray-json" % "10.1.8",
      "com.typesafe.akka" %% "akka-http-xml" % "10.1.8",
      "com.typesafe.akka" %% "akka-stream-typed" % "2.5.22",
      "com.thesamet.scalapb" %% "scalapb-json4s" % "0.7.2",
      "org.json4s" %% "json4s-native" % "3.6.5",
      "de.heikoseeberger" %% "akka-http-json4s" % "1.25.2",
      "com.nimbusds" % "nimbus-jose-jwt" % "7.1"
    ),
    dependencyOverrides += "com.zaxxer" % "HikariCP" % "2.7.8"
  )
  .settings(libraryDependencies ++= Seq(
    "com.h2database" % "h2" % "1.4.199" % Test,
    "com.typesafe.akka" %% "akka-actor-testkit-typed" % "2.5.22" % Test,
    "com.github.dnvriend" %% "akka-persistence-inmemory" % "2.5.1.1" % Test,
    "org.scalatest" %% "scalatest" % "3.0.7" % Test,
    "com.typesafe.akka" %% "akka-http-testkit" % "10.1.8" % Test,
    "com.typesafe.akka" %% "akka-stream-testkit" % "2.5.22" % Test
  ))
  .configs(IntegrationTest)
  .settings(Defaults.itSettings)
  .settings(inConfig(IntegrationTest)(ScalafmtPlugin.scalafmtConfigSettings))
  .settings(IntegrationTest / logBuffered := false)
  .settings(libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.0.7" % IntegrationTest,
    "com.typesafe.akka" %% "akka-http-testkit" % "10.1.8" % IntegrationTest,
    "com.typesafe.akka" %% "akka-stream-testkit" % "2.5.22" % IntegrationTest
  ))
  .enablePlugins(
    BuildInfoPlugin,
    JavaAppPackaging,
    DockerPlugin
  )
  .settings(
    buildInfoPackage := "com.dhpcs.liquidity.service",
    buildInfoUsePackageAsPath := true,
    buildInfoKeys := Seq(version),
    buildInfoOptions ++= Seq(BuildInfoOption.BuildTime, BuildInfoOption.ToMap),
    bashScriptExtraDefines += "addJava -Djdk.tls.ephemeralDHKeySize=2048",
    Docker / packageName := "liquidity",
    dockerBaseImage := "openjdk:12-jdk-oracle",
    dockerExposedPorts := Seq(8443)
  )
