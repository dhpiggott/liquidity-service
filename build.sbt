lazy val protobufSettings = Seq(
  PB.targets in Compile := Seq(
    scalapb.gen(flatPackage = true, singleLineToString = true) -> (sourceManaged in Compile).value
  ),
  libraryDependencies += "com.trueaccord.scalapb"          %% "scalapb-runtime" %
    com.trueaccord.scalapb.compiler.Version.scalapbVersion % ProtocPlugin.ProtobufConfig
)

lazy val noopPublishSetting = publishM2 := {}

lazy val `proto-binding` = project
  .in(file("proto-binding"))
  .settings(
    name := "liquidity-proto-binding"
  )
  .settings(
    libraryDependencies ++= Seq(
      "com.chuusai"   %% "shapeless" % "2.3.2",
      "org.typelevel" %% "cats-core" % "0.9.0"
    ))

lazy val model = project
  .in(file("model"))
  .settings(protobufSettings)
  .settings(
    name := "liquidity-model"
  )
  .dependsOn(`proto-binding`)
  .settings(
    libraryDependencies ++= Seq(
      "com.squareup.okio" % "okio"        % "1.13.0",
      "com.typesafe.akka" %% "akka-actor" % "2.5.4"
    )
  )

lazy val `ws-protocol` = project
  .in(file("ws-protocol"))
  .settings(protobufSettings)
  .settings(
    name := "liquidity-ws-protocol"
  )
  .settings(
    PB.includePaths in Compile += file("model/src/main/protobuf")
  )
  .dependsOn(model)
  .settings(libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.4" % Test)

lazy val `ws-legacy-protocol` = project
  .in(file("ws-legacy-protocol"))
  .settings(noopPublishSetting)
  .settings(
    name := "liquidity-ws-legacy-protocol"
  )
  .dependsOn(model)
  .settings(libraryDependencies += "com.dhpcs" %% "scala-json-rpc" % "2.0.0")
  .dependsOn(`ws-protocol` % "test->test")
  .settings(libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.4" % Test)

lazy val `actor-protocol` = project
  .in(file("actor-protocol"))
  .settings(protobufSettings)
  .settings(
    name := "liquidity-actor-protocol"
  )
  .settings(
    PB.includePaths in Compile += file("model/src/main/protobuf"),
    PB.includePaths in Compile += file("ws-protocol/src/main/protobuf")
  )
  .dependsOn(`ws-protocol`)
  .settings(libraryDependencies += "com.typesafe.akka" %% "akka-typed" % "2.5.4")

lazy val persistence = project
  .in(file("persistence"))
  .settings(noopPublishSetting)
  .settings(protobufSettings)
  .settings(
    name := "liquidity-persistence"
  )
  .settings(
    PB.includePaths in Compile += file("model/src/main/protobuf")
  )
  .dependsOn(model)
  .settings(libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.4" % Test)

lazy val testkit = project
  .in(file("testkit"))
  .settings(noopPublishSetting)
  .settings(
    name := "liquidity-testkit"
  )
  .settings(libraryDependencies += "org.bouncycastle" % "bcpkix-jdk15on" % "1.58")

lazy val server = project
  .in(file("server"))
  .settings(noopPublishSetting)
  .settings(
    name := "liquidity-server"
  )
  .dependsOn(`ws-protocol`)
  .dependsOn(`ws-legacy-protocol`)
  .dependsOn(`actor-protocol`)
  .dependsOn(persistence)
  .settings(
    dependencyOverrides ++= Seq(
      "org.scala-lang.modules"     %% "scala-xml"                   % "1.0.6",
      "com.lihaoyi"                %% "fastparse"                   % "0.4.3",
      "com.fasterxml.jackson.core" % "jackson-databind"             % "2.8.9",
      "com.github.jnr"             % "jnr-constants"                % "0.9.6",
      "com.github.jnr"             % "jnr-ffi"                      % "2.1.2",
      "org.slf4j"                  % "slf4j-api"                    % "1.7.25",
      "com.typesafe.akka"          %% "akka-actor"                  % "2.5.4",
      "com.typesafe.akka"          %% "akka-cluster"                % "2.5.4",
      "com.typesafe.akka"          %% "akka-cluster-sharding"       % "2.5.4",
      "com.typesafe.akka"          %% "akka-stream"                 % "2.5.4",
      "com.typesafe.akka"          %% "akka-stream-testkit"         % "2.5.4",
      "com.typesafe.akka"          %% "akka-persistence"            % "2.5.4",
      "com.typesafe.akka"          %% "akka-persistence-query"      % "2.5.4",
      "com.typesafe.akka"          %% "akka-http"                   % "10.0.10",
      "io.netty"                   % "netty-transport-native-epoll" % "4.0.44.Final",
      "com.google.protobuf"        % "protobuf-java"                % "3.3.1",
      "com.trueaccord.scalapb"     %% "scalapb-runtime"             % "0.6.2",
      "com.typesafe.play"          %% "play-json"                   % "2.6.5"
    ),
    libraryDependencies ++= Seq(
      "com.typesafe.akka"      %% "akka-slf4j"                   % "2.5.4",
      "ch.qos.logback"         % "logback-classic"               % "1.2.3",
      "com.typesafe.akka"      %% "akka-actor"                   % "2.5.4",
      "com.typesafe.akka"      %% "akka-cluster"                 % "2.5.4",
      "com.lightbend.akka"     %% "akka-management-cluster-http" % "0.4",
      "com.typesafe.akka"      %% "akka-cluster-sharding"        % "2.5.4",
      "com.typesafe.akka"      %% "akka-cluster-tools"           % "2.5.4",
      "com.typesafe.akka"      %% "akka-persistence"             % "2.5.4",
      "com.typesafe.akka"      %% "akka-persistence-query"       % "2.5.4",
      "com.typesafe.akka"      %% "akka-persistence-cassandra"   % "0.55",
      "io.netty"               % "netty-transport-native-epoll"  % "4.1.12.Final" classifier "linux-x86_64",
      "com.datastax.cassandra" % "cassandra-driver-core"         % "3.2.0",
      "com.typesafe.akka"      %% "akka-http"                    % "10.0.10",
      "com.trueaccord.scalapb" %% "scalapb-json4s"               % "0.3.2",
      "com.typesafe.play"      %% "play-json"                    % "2.6.5",
      "de.heikoseeberger"      %% "akka-http-play-json"          % "1.17.0"
    )
  )
  .dependsOn(`ws-protocol` % "test->test")
  .dependsOn(testkit % "test")
  .settings(libraryDependencies ++= Seq(
    "com.github.dnvriend" %% "akka-persistence-inmemory" % "2.5.1.1" % Test,
    "org.scalatest"       %% "scalatest"                 % "3.0.4"   % Test,
    "com.typesafe.akka"   %% "akka-http-testkit"         % "10.0.10" % Test,
    "com.typesafe.akka"   %% "akka-stream-testkit"       % "2.5.4"   % Test
  ))
  .enablePlugins(MultiJvmPlugin)
  .configs(MultiJvm)
  .settings(inConfig(MultiJvm)(scalafmtSettings))
  .dependsOn(testkit % MultiJvm)
  .settings(libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-multi-node-testkit"             % "2.5.4" % MultiJvm,
    "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % "0.55"  % MultiJvm
  ))
  .enablePlugins(BuildInfoPlugin, JavaAppPackaging, DockerPlugin)
  .settings(
    buildInfoPackage := "com.dhpcs.liquidity.server",
    buildInfoUsePackageAsPath := true,
    buildInfoKeys := Seq(version),
    buildInfoOptions ++= Seq(BuildInfoOption.BuildTime, BuildInfoOption.ToMap),
    bashScriptExtraDefines ++= Seq(
      "addJava -Djdk.tls.ephemeralDHKeySize=2048",
      "addJava -Djdk.tls.rejectClientInitiatedRenegotiation=true"
    ),
    dockerBaseImage := "openjdk:8-jre",
    daemonUser in Docker := "root"
  )

lazy val client = project
  .in(file("client"))
  .settings(
    name := "liquidity-client"
  )
  .dependsOn(`ws-protocol`)
  .settings(
    dependencyOverrides += "org.scala-lang.modules" %% "scala-xml" % "1.0.6",
    libraryDependencies ++= Seq(
      "com.madgag.spongycastle" % "pkix"   % "1.54.0.0",
      "com.squareup.okhttp3"    % "okhttp" % "3.9.0"
    )
  )
  .dependsOn(testkit % "test")
  .dependsOn(server % "test->test")
  .settings(libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.4" % Test)

lazy val healthcheck = project
  .in(file("healthcheck"))
  .settings(noopPublishSetting)
  .settings(
    name := "liquidity-healthcheck"
  )
  .dependsOn(client)
  .dependsOn(`ws-legacy-protocol`)
  .settings(
    dependencyOverrides ++= Seq(
      "org.scala-lang.modules" %% "scala-xml" % "1.0.6",
      "com.typesafe.play"      %% "play-json" % "2.6.4"
    ),
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-stream-testkit" % "2.5.4",
      "org.scalatest"     %% "scalatest"           % "3.0.4"
    )
  )
  .dependsOn(testkit % "test")
  .dependsOn(server % "test->test")
  .enablePlugins(JavaAppPackaging, UniversalPlugin)

lazy val boardgame = project
  .in(file("boardgame"))
  .settings(
    name := "liquidity-boardgame"
  )
  .dependsOn(client)

lazy val root = project
  .in(file("."))
  .settings(noopPublishSetting)
  .settings(
    name := "liquidity"
  )
  .aggregate(
    `proto-binding`,
    model,
    persistence,
    `actor-protocol`,
    `ws-protocol`,
    `ws-legacy-protocol`,
    testkit,
    server,
    client,
    healthcheck,
    boardgame
  )
