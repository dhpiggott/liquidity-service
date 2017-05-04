lazy val commonSettings = Seq(
  scalaVersion := "2.12.2",
  crossScalaVersions := Seq("2.11.11", "2.12.2"),
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
  addCommandAlias("validate", ";scalafmtTest; coverage; test; multi-jvm:test; coverageReport") ++
  addCommandAlias("validateAggregate", ";coverageAggregate")

lazy val protobufSettings = Seq(
  PB.targets in Compile := Seq(
    scalapb.gen(flatPackage = true, singleLineToString = true) -> (sourceManaged in Compile).value
  ),
  libraryDependencies += "com.trueaccord.scalapb" %% "scalapb-runtime" % com.trueaccord.scalapb.compiler.Version.scalapbVersion % ProtocPlugin.ProtobufConfig
)

lazy val publishSettings = Seq(
  organization := "com.dhpcs"
)

lazy val noopPublishSettings = Seq(
  publishM2 := {}
)

lazy val playJson = "com.typesafe.play" %% "play-json" % "2.6.0-M7"

lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.0.3"

lazy val model = project
  .in(file("model"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(protobufSettings)
  .settings(
    name := "liquidity-model"
  )
  .settings(
    libraryDependencies ++= Seq(
      "com.squareup.okio" % "okio"       % "1.12.0",
      "com.chuusai"       %% "shapeless" % "2.3.2",
      playJson
    ))
  .settings(libraryDependencies += scalaTest % Test)

lazy val persistence = project
  .in(file("persistence"))
  .settings(commonSettings)
  .settings(noopPublishSettings)
  .settings(protobufSettings)
  .settings(
    name := "liquidity-persistence"
  )
  .settings(
    PB.includePaths in Compile += file("model/src/main/protobuf")
  )
  .settings(libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-actor"       % "2.4.18",
    "com.typesafe.akka" %% "akka-persistence" % "2.4.18"
  ))
  .dependsOn(model)
  .settings(libraryDependencies += scalaTest % Test)
  .dependsOn(model % "test->test")

lazy val `ws-protocol` = project
  .in(file("ws-protocol"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name := "liquidity-ws-protocol"
  )
  .settings(libraryDependencies +=
    "com.dhpcs" %% "scala-json-rpc" % "2.0-M4")
  .dependsOn(model)
  .settings(libraryDependencies += scalaTest % Test)

lazy val `actor-protocol` = project
  .in(file("actor-protocol"))
  .settings(commonSettings)
  .settings(noopPublishSettings)
  .settings(
    name := "liquidity-actor-protocol"
  )
  .settings(libraryDependencies +=
    "com.typesafe.akka" %% "akka-actor" % "2.4.18")
  .dependsOn(model)
  .dependsOn(`ws-protocol`)

lazy val certgen = project
  .in(file("certgen"))
  .settings(commonSettings)
  .settings(noopPublishSettings)
  .settings(
    name := "liquidity-certgen"
  )
  .settings(libraryDependencies +=
    "org.bouncycastle" % "bcpkix-jdk15on" % "1.56")
  .settings(libraryDependencies += scalaTest % Test)

lazy val server = project
  .in(file("server"))
  .settings(commonSettings)
  .settings(noopPublishSettings)
  .settings(
    name := "liquidity-server"
  )
  .dependsOn(model)
  .dependsOn(persistence)
  .dependsOn(`ws-protocol`)
  .dependsOn(`actor-protocol`)
  .settings(libraryDependencies ++= Seq(
    "com.typesafe.akka"        %% "akka-slf4j"                          % "2.4.18",
    "ch.qos.logback"           % "logback-classic"                      % "1.2.3",
    "com.typesafe.akka"        %% "akka-actor"                          % "2.4.18",
    "com.typesafe.akka"        %% "akka-cluster"                        % "2.4.18",
    "com.typesafe.akka"        %% "akka-cluster-sharding"               % "2.4.18",
    "com.typesafe.akka"        %% "akka-cluster-tools"                  % "2.4.18",
    "com.typesafe.akka"        %% "akka-distributed-data-experimental"  % "2.4.18",
    "com.typesafe.akka"        %% "akka-persistence"                    % "2.4.18",
    "com.typesafe.akka"        %% "akka-persistence-query-experimental" % "2.4.18",
    "com.typesafe.akka"        %% "akka-persistence-cassandra"          % "0.27",
    "io.netty"                 % "netty-transport-native-epoll"         % "4.1.10.Final" classifier "linux-x86_64",
    "com.google.code.findbugs" % "jsr305"                               % "3.0.2" % Compile,
    "com.datastax.cassandra"   % "cassandra-driver-core"                % "3.2.0",
    "com.typesafe.akka"        %% "akka-http"                           % "10.0.6",
    playJson
  ))
  .dependsOn(certgen % "test")
  .settings(libraryDependencies ++= Seq(
    scalaTest           % Test,
    "com.typesafe.akka" %% "akka-http-testkit" % "10.0.6" % Test
  ))
  .configs(MultiJvm)
  .settings(SbtMultiJvm.multiJvmSettings)
  .dependsOn(certgen % MultiJvm)
  .settings(libraryDependencies ++= Seq(
    scalaTest              % MultiJvm,
    "com.typesafe.akka"    %% "akka-multi-node-testkit" % "2.4.18" % MultiJvm,
    "org.apache.cassandra" % "cassandra-all" % "3.10" % MultiJvm exclude ("io.netty", "netty-all")
  ))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    bashScriptExtraDefines ++= Seq(
      "addJava -Djdk.tls.ephemeralDHKeySize=2048",
      "addJava -Djdk.tls.rejectClientInitiatedRenegotiation=true"
    ),
    dockerBaseImage := "openjdk:8-jre",
    daemonUser in Docker := "root"
  )

lazy val client = project
  .in(file("client"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name := "liquidity-client"
  )
  .dependsOn(model)
  .dependsOn(`ws-protocol`)
  .settings(libraryDependencies ++= Seq(
    "com.madgag.spongycastle" % "pkix"      % "1.54.0.0",
    "com.squareup.okhttp3"    % "okhttp-ws" % "3.4.2"
  ))
  .dependsOn(certgen % "test")
  .dependsOn(server % "test->test")
  .settings(libraryDependencies += scalaTest % Test)

lazy val healthcheck = project
  .in(file("healthcheck"))
  .settings(commonSettings)
  .settings(noopPublishSettings)
  .settings(
    name := "liquidity-healthcheck"
  )
  .dependsOn(client)
  .settings(
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-stream-testkit" % "2.4.18",
      scalaTest
    ))
  .enablePlugins(JavaAppPackaging, UniversalPlugin)

lazy val boardgame = project
  .in(file("boardgame"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name := "liquidity-boardgame"
  )
  .dependsOn(client)

lazy val root = project
  .in(file("."))
  .settings(commonSettings)
  .settings(noopPublishSettings)
  .settings(
    name := "liquidity"
  )
  .aggregate(
    model,
    persistence,
    `ws-protocol`,
    `actor-protocol`,
    certgen,
    server,
    client,
    healthcheck,
    boardgame
  )
