lazy val commonSettings = Seq(
    scalaVersion := "2.11.8",
    scalacOptions in Compile ++= Seq(
      "-target:jvm-1.8",
      "-encoding",
      "UTF-8",
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xfuture",
      "-Xlint",
      "-Yno-adapted-args",
      "-Ywarn-dead-code",
      "-Ywarn-inaccessible",
      "-Ywarn-infer-any",
      "-Ywarn-nullary-override",
      "-Ywarn-nullary-unit",
      "-Ywarn-numeric-widen",
      "-Ywarn-unused",
      "-Ywarn-unused-import"
    ),
    resolvers += Resolver.bintrayRepo("dhpcs", "maven")
  ) ++
    addCommandAlias("validate", ";scalafmtTest; coverage; test; it:test; coverageReport") ++
    addCommandAlias("validateAggregate", ";coverageAggregate")

lazy val publishSettings = Seq(
  startYear := Some(2015),
  organization := "com.dhpcs",
  organizationHomepage := Some(url("https://www.dhpcs.com/")),
  organizationName := "dhpcs",
  developers := List(
    Developer(
      id = "dhpiggott",
      name = "David Piggott",
      email = "david@piggott.me.uk",
      url = url("https://dhpiggott.net/")
    ))
)

lazy val noopPublishSettings = Seq(
  publishArtifact := false,
  publish := {},
  publishLocal := {}
)

lazy val akkaPersistenceSettings = libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-slf4j"                          % "2.4.16",
    "ch.qos.logback"    % "logback-classic"                      % "1.1.9",
    "com.typesafe.akka" %% "akka-persistence"                    % "2.4.16",
    "com.typesafe.akka" %% "akka-cluster-tools"                  % "2.4.16",
    "com.typesafe.akka" %% "akka-persistence-query-experimental" % "2.4.16",
    "com.typesafe.akka" %% "akka-persistence-cassandra"          % "0.22",
    "io.netty"          % "netty-transport-native-epoll"         % "4.1.7.Final" classifier "linux-x86_64"
  )

lazy val akkaClusterShardingSettings = libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-cluster-sharding"              % "2.4.16",
    "com.typesafe.akka" %% "akka-distributed-data-experimental" % "2.4.16"
  )

lazy val dockerSettings = dockerBaseImage := "openjdk:8-jre"

lazy val playJson = "com.typesafe.play" %% "play-json" % "2.3.10"

lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.0.1"

lazy val liquidityModel = project
  .in(file("model"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name := "liquidity-model"
  )
  .settings(
    libraryDependencies ++= Seq(
      playJson,
      "com.squareup.okio" % "okio" % "1.11.0"
    ))
  .settings(
    libraryDependencies ++= Seq(
      scalaTest   % Test,
      "com.dhpcs" %% "play-json-rpc-testkit" % "1.3.0" % Test
    ))

lazy val liquidityPersistence = project
  .in(file("persistence"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name := "liquidity-persistence"
  )
  .settings(libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-actor" % "2.4.16"
  ))
  .dependsOn(liquidityModel)

lazy val liquidityProtocol = project
  .in(file("protocol"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name := "liquidity-protocol"
  )
  .settings(libraryDependencies ++= Seq(
    "com.dhpcs" %% "play-json-rpc" % "1.3.0"
  ))
  .dependsOn(liquidityModel)
  .settings(
    libraryDependencies ++= Seq(
      scalaTest   % Test,
      "com.dhpcs" %% "play-json-rpc-testkit" % "1.3.0" % Test
    ))

lazy val liquidityCertgen = project
  .in(file("certgen"))
  .settings(commonSettings)
  .settings(noopPublishSettings)
  .settings(
    name := "liquidity-certgen"
  )
  .settings(libraryDependencies ++= Seq(
    "org.bouncycastle" % "bcpkix-jdk15on" % "1.56"
  ))
  .settings(libraryDependencies ++= Seq(
    scalaTest % Test
  ))

lazy val liquidityServer = project
  .in(file("server"))
  .settings(commonSettings)
  .settings(noopPublishSettings)
  .settings(
    name := "liquidity-server"
  )
  .dependsOn(liquidityModel)
  .dependsOn(liquidityPersistence)
  .dependsOn(liquidityProtocol)
  .settings(akkaPersistenceSettings)
  .settings(akkaClusterShardingSettings)
  .settings(libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-http" % "10.0.3",
    playJson
  ))
  .settings(libraryDependencies ++= Seq(
    scalaTest           % Test,
    "com.typesafe.akka" %% "akka-http-testkit" % "10.0.3" % Test,
    "org.iq80.leveldb"  % "leveldb" % "0.9" % Test
  ))
  .configs(IntegrationTest)
  .settings(Defaults.itSettings)
  .dependsOn(liquidityCertgen % "it")
  .settings(fork in IntegrationTest := true)
  .settings(libraryDependencies ++= Seq(
    scalaTest              % IntegrationTest,
    "org.apache.cassandra" % "cassandra-all" % "3.7" % IntegrationTest
  ))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(dockerSettings)
  .settings(
    daemonUser in Docker := "root",
    bashScriptExtraDefines ++= Seq(
      "addJava -Djdk.tls.ephemeralDHKeySize=2048",
      "addJava -Djdk.tls.rejectClientInitiatedRenegotiation=true"
    )
  )

lazy val liquidityClient = project
  .in(file("client"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name := "liquidity-client"
  )
  .dependsOn(liquidityModel)
  .dependsOn(liquidityProtocol)
  .settings(libraryDependencies ++= Seq(
    "com.madgag.spongycastle" % "pkix"      % "1.54.0.0",
    "com.squareup.okhttp3"    % "okhttp-ws" % "3.4.2"
  ))
  .dependsOn(liquidityCertgen % "test")
  .settings(libraryDependencies ++= Seq(
    scalaTest % Test
  ))
  .configs(IntegrationTest)
  .settings(Defaults.itSettings)
  .dependsOn(liquidityServer % "it->it")
  .settings(fork in IntegrationTest := true)
  .settings(libraryDependencies ++= Seq(
    scalaTest % IntegrationTest
  ))

lazy val liquidityHealthcheck = project
  .in(file("healthcheck"))
  .settings(commonSettings)
  .settings(noopPublishSettings)
  .settings(
    name := "liquidity-healthcheck"
  )
  .dependsOn(liquidityClient)
  .settings(
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-stream-testkit" % "2.4.16",
      scalaTest
    ))
  .enablePlugins(JavaAppPackaging, UniversalPlugin)

lazy val liquidityBoardgame = project
  .in(file("boardgame"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name := "liquidity-boardgame"
  )
  .dependsOn(liquidityClient)

lazy val liquidityAnalytics = project
  .in(file("analytics"))
  .settings(commonSettings)
  .settings(noopPublishSettings)
  .settings(
    name := "liquidity-analytics"
  )
  .dependsOn(liquidityModel)
  .dependsOn(liquidityPersistence)
  .settings(akkaPersistenceSettings)
  .settings(akkaClusterShardingSettings)
  .settings(libraryDependencies ++= Seq(
    "com.datastax.cassandra"   % "cassandra-driver-core" % "3.1.3",
    "com.google.code.findbugs" % "jsr305"                % "3.0.1" % Compile
  ))
  .settings(unmanagedClasspath in Compile ++= (unmanagedResources in Compile).value)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(dockerSettings)

lazy val root = project
  .in(file("."))
  .settings(commonSettings)
  .settings(noopPublishSettings)
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
    liquidityHealthcheck,
    liquidityBoardgame,
    liquidityAnalytics
  )
