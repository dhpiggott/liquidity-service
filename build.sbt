lazy val commonSettings = Seq(
  scalaVersion := "2.11.8",
  organization := "com.dhpcs",
  resolvers += Resolver.bintrayRepo("dhpcs", "maven")
)

lazy val noopPublish = Seq(
  publishArtifact := false,
  publish := {},
  publishLocal := {}
)

lazy val playJson = "com.typesafe.play" %% "play-json" % "2.3.10"

lazy val akkaPersistenceCassandra = "com.typesafe.akka" %% "akka-persistence-cassandra" % "0.18"

lazy val akkaSlf4j = "com.typesafe.akka" %% "akka-slf4j" % "2.4.10"

lazy val logback = "ch.qos.logback" % "logback-classic" % "1.1.7"

lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.0.0"

lazy val openJdk8 = "openjdk:8-jre"

lazy val liquidityModel = project.in(file("model"))
  .settings(commonSettings)
  .settings(
    name := "liquidity-model",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor" % "2.4.10",
      playJson,
      "com.squareup.okio" % "okio" % "1.10.0",
      scalaTest % Test,
      // TODO
      "com.dhpcs" %% "play-json-rpc-testkit" % "1.2.1" % Test
    )
  )

lazy val liquidityProtocol = project.in(file("protocol"))
  .settings(commonSettings)
  .settings(
    name := "liquidity-protocol",
    libraryDependencies ++= Seq(
      "com.dhpcs" %% "play-json-rpc" % "1.2.1",
      scalaTest % Test,
      "com.dhpcs" %% "play-json-rpc-testkit" % "1.2.1" % Test
    )
  )
  .dependsOn(liquidityModel)

lazy val liquidityCertgen = project.in(file("certgen"))
  .settings(commonSettings)
  .settings(noopPublish)
  .settings(
    name := "liquidity-certgen",
    libraryDependencies ++= Seq(
      "org.bouncycastle" % "bcpkix-jdk15on" % "1.55"
    )
  )

lazy val liquidityServer = project.in(file("server"))
  .settings(commonSettings)
  .settings(noopPublish)
  .settings(
    name := "liquidity-server",
    libraryDependencies ++= Seq(
      akkaSlf4j,
      logback,
      "com.typesafe.akka" %% "akka-http-experimental" % "2.4.10",
      playJson,
      "com.typesafe.akka" %% "akka-cluster-sharding" % "2.4.10",
      "com.typesafe.akka" %% "akka-distributed-data-experimental" % "2.4.10",
      akkaPersistenceCassandra,
      scalaTest % Test,
      "com.typesafe.akka" %% "akka-http-testkit" % "2.4.10" % Test,
      "org.iq80.leveldb" % "leveldb" % "0.9" % Test
    ),
    dockerBaseImage := openJdk8,
    dockerExposedPorts := Seq(443),
    daemonUser in Docker := "root",
    bashScriptExtraDefines += "addJava -Djdk.tls.ephemeralDHKeySize=2048"
  )
  .dependsOn(liquidityModel)
  .dependsOn(liquidityProtocol)
  .dependsOn(liquidityCertgen % Test)
  .enablePlugins(JavaAppPackaging, DockerPlugin)

lazy val liquidityBoardgame = project.in(file("boardgame"))
  .settings(commonSettings)
  .settings(
    name := "liquidity-boardgame",
    libraryDependencies ++= Seq(
      "com.madgag.spongycastle" % "pkix" % "1.54.0.0",
      "com.squareup.okhttp3" % "okhttp-ws" % "3.4.1"
    )
  )
  .dependsOn(liquidityProtocol)

lazy val liquidityAnalytics = project.in(file("analytics"))
  .settings(commonSettings)
  .settings(noopPublish)
  .settings(
    name := "liquidity-analytics",
    libraryDependencies ++= Seq(
      akkaSlf4j,
      logback,
      akkaPersistenceCassandra
    ),
    dockerBaseImage := openJdk8
  )
  .dependsOn(liquidityModel)
  .enablePlugins(JavaAppPackaging, DockerPlugin)

lazy val root = project.in(file("."))
  .settings(commonSettings)
  .settings(noopPublish)
  .settings(
    name := "liquidity"
  )
  .aggregate(
    liquidityModel,
    liquidityProtocol,
    liquidityCertgen,
    liquidityServer,
    liquidityBoardgame,
    liquidityAnalytics
  )
