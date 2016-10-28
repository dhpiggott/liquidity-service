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

lazy val serverAppSettings =
  commonSettings ++
  noopPublish ++ Seq(
    name := "liquidity-analytics",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-slf4j" % "2.4.12",
      "ch.qos.logback" % "logback-classic" % "1.1.7",
      "com.typesafe.akka" %% "akka-persistence" % "2.4.12",
      "com.typesafe.akka" %% "akka-cluster-tools" % "2.4.12",
      "com.typesafe.akka" %% "akka-persistence-query-experimental" % "2.4.12",
      "com.typesafe.akka" %% "akka-persistence-cassandra" % "0.19",
      "io.netty" % "netty-transport-native-epoll" % "4.1.6.Final" classifier "linux-x86_64"
    ),
    dockerBaseImage := "openjdk:8-jre"
  )

lazy val playJson = "com.typesafe.play" %% "play-json" % "2.3.10"

lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.0.0"

lazy val liquidityModel = project.in(file("model"))
  .settings(commonSettings)
  .settings(
    name := "liquidity-model",
    libraryDependencies ++= Seq(
      playJson,
      "com.squareup.okio" % "okio" % "1.11.0",
      scalaTest % Test,
      "com.dhpcs" %% "play-json-rpc-testkit" % "1.2.1" % Test
    )
  )

lazy val liquidityPersistence = project.in(file("persistence"))
  .settings(commonSettings)
  .settings(
    name := "liquidity-persistence",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor" % "2.4.12"
    )
  )
  .dependsOn(liquidityModel)

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
      "org.bouncycastle" % "bcpkix-jdk15on" % "1.55",
      scalaTest % Test
    )
  )

lazy val liquidityServer = project.in(file("server"))
  .settings(serverAppSettings)
  .settings(
    name := "liquidity-server",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-http-experimental" % "2.4.11",
      playJson,
      "com.typesafe.akka" %% "akka-cluster-sharding" % "2.4.12",
      "com.typesafe.akka" %% "akka-distributed-data-experimental" % "2.4.12",
      scalaTest % Test,
      "com.typesafe.akka" %% "akka-http-testkit" % "2.4.11" % Test,
      "org.iq80.leveldb" % "leveldb" % "0.9" % Test
    ),
    dockerExposedPorts := Seq(443),
    daemonUser in Docker := "root",
    bashScriptExtraDefines ++= Seq(
      "addJava -Djdk.tls.ephemeralDHKeySize=2048",
      "addJava -Djdk.tls.rejectClientInitiatedRenegotiation=true"
    )
  )
  .dependsOn(liquidityModel)
  .dependsOn(liquidityPersistence)
  .dependsOn(liquidityProtocol)
  .dependsOn(liquidityCertgen % Test)
  .enablePlugins(JavaAppPackaging, DockerPlugin)

lazy val liquidityClient = project.in(file("client"))
  .settings(commonSettings)
  .settings(
    name := "liquidity-client",
    libraryDependencies ++= Seq(
      "com.madgag.spongycastle" % "pkix" % "1.54.0.0",
      "com.squareup.okhttp3" % "okhttp-ws" % "3.4.1",
      scalaTest % Test,
      "org.iq80.leveldb" % "leveldb" % "0.9" % Test
    )
  )
  .dependsOn(liquidityModel)
  .dependsOn(liquidityProtocol)
  .dependsOn(liquidityCertgen % Test)
  .dependsOn(liquidityServer % Test)

lazy val liquidityBoardgame = project.in(file("boardgame"))
  .settings(commonSettings)
  .settings(
    name := "liquidity-boardgame"
  )
  .dependsOn(liquidityClient)

lazy val liquidityAnalytics = project.in(file("analytics"))
  .settings(serverAppSettings)
  .settings(
    name := "liquidity-analytics"
  )
  .dependsOn(liquidityModel)
  .dependsOn(liquidityPersistence)
  .enablePlugins(JavaAppPackaging, DockerPlugin)

lazy val root = project.in(file("."))
  .settings(commonSettings)
  .settings(noopPublish)
  .settings(
    name := "liquidity"
  )
  .aggregate(
    liquidityModel,
    liquidityPersistence,
    liquidityProtocol,
    liquidityCertgen,
    liquidityServer,
    liquidityClient,
    liquidityBoardgame,
    liquidityAnalytics
  )
