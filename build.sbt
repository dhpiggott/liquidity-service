lazy val protobufSettings = Seq(
  PB.targets in Compile := Seq(
    scalapb.gen(flatPackage = true, singleLineToString = true) -> (sourceManaged in Compile).value
  ),
  libraryDependencies += "com.trueaccord.scalapb" %% "scalapb-runtime" % com.trueaccord.scalapb.compiler.Version.scalapbVersion % ProtocPlugin.ProtobufConfig
)

lazy val noopPublishSetting = publishM2 := {}

lazy val serialization = project
  .in(file("serialization"))
  .settings(noopPublishSetting)
  .settings(protobufSettings)
  .settings(
    name := "liquidity-serialization"
  )
  .settings(
    libraryDependencies ++= Seq(
      "com.chuusai"       %% "shapeless"   % "2.3.2",
      "com.typesafe.akka" %% "akka-actor"  % "2.5.2",
      "com.typesafe.akka" %% "akka-remote" % "2.5.2"
    ))
  .settings(libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.3" % Test)

lazy val model = project
  .in(file("model"))
  .settings(protobufSettings)
  .settings(
    name := "liquidity-model"
  )
  .dependsOn(serialization)
  .settings(libraryDependencies += "com.squareup.okio" % "okio" % "1.13.0")
  .settings(libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.3" % Test)

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
  .dependsOn(serialization)
  .dependsOn(model)
  .dependsOn(model % "test->test")
  .settings(libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.3" % Test)

lazy val actorProtocol = project
  .in(file("actor-protocol"))
  .settings(noopPublishSetting)
  .settings(protobufSettings)
  .settings(
    name := "liquidity-actor-protocol"
  )
  .settings(
    PB.includePaths in Compile += file("model/src/main/protobuf")
  )
  .dependsOn(serialization)
  .dependsOn(model)

lazy val wsProtocol = project
  .in(file("ws-protocol"))
  .settings(protobufSettings)
  .settings(
    name := "liquidity-ws-protocol"
  )
  .settings(
    PB.includePaths in Compile += file("model/src/main/protobuf")
  )
  .dependsOn(serialization)
  .dependsOn(model)
  .dependsOn(actorProtocol)
  .dependsOn(model % "test->test")
  .settings(libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.3" % Test)

lazy val wsLegacyProtocol = project
  .in(file("ws-legacy-protocol"))
  .settings(
    name := "liquidity-ws-legacy-protocol"
  )
  .dependsOn(serialization)
  .dependsOn(model)
  .dependsOn(actorProtocol)
  .settings(libraryDependencies += "com.dhpcs" %% "scala-json-rpc" % "2.0-RC1")
  .dependsOn(model % "test->test")
  .settings(libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.3" % Test)

lazy val certgen = project
  .in(file("certgen"))
  .settings(noopPublishSetting)
  .settings(
    name := "liquidity-certgen"
  )
  .settings(libraryDependencies += "org.bouncycastle" % "bcpkix-jdk15on" % "1.57")
  .settings(libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.3" % Test)

lazy val server = project
  .in(file("server"))
  .settings(noopPublishSetting)
  .settings(
    name := "liquidity-server"
  )
  .dependsOn(model)
  .dependsOn(persistence)
  .dependsOn(actorProtocol)
  .dependsOn(wsProtocol)
  .dependsOn(wsLegacyProtocol)
  .settings(
    dependencyOverrides ++= Set(
      "org.scala-lang.modules"     %% "scala-xml"                   % "1.0.6",
      "com.fasterxml.jackson.core" % "jackson-databind"             % "2.8.8",
      "com.github.jnr"             % "jnr-constants"                % "0.9.6",
      "com.github.jnr"             % "jnr-ffi"                      % "2.1.2",
      "org.slf4j"                  % "slf4j-api"                    % "1.7.25",
      "com.typesafe.akka"          %% "akka-actor"                  % "2.5.2",
      "com.typesafe.akka"          %% "akka-cluster"                % "2.5.2",
      "com.typesafe.akka"          %% "akka-cluster-tools"          % "2.5.2",
      "com.typesafe.akka"          %% "akka-stream"                 % "2.5.2",
      "com.typesafe.akka"          %% "akka-stream-testkit"         % "2.5.2",
      "com.typesafe.akka"          %% "akka-persistence"            % "2.5.2",
      "com.typesafe.akka"          %% "akka-persistence-query"      % "2.5.2",
      "com.typesafe.akka"          %% "akka-http"                   % "10.0.7",
      "com.datastax.cassandra"     % "cassandra-driver-core"        % "3.2.0",
      "io.netty"                   % "netty-transport-native-epoll" % "4.0.44.Final"
    ),
    libraryDependencies ++= Seq(
      "com.typesafe.akka"      %% "akka-slf4j"                   % "2.5.2",
      "ch.qos.logback"         % "logback-classic"               % "1.2.3",
      "com.typesafe.akka"      %% "akka-actor"                   % "2.5.2",
      "com.typesafe.akka"      %% "akka-cluster"                 % "2.5.2",
      "com.lightbend.akka"     %% "akka-management-cluster-http" % "0.3",
      "com.typesafe.akka"      %% "akka-cluster-sharding"        % "2.5.2",
      "com.typesafe.akka"      %% "akka-cluster-tools"           % "2.5.2",
      "com.typesafe.akka"      %% "akka-distributed-data"        % "2.5.2",
      "com.typesafe.akka"      %% "akka-persistence"             % "2.5.2",
      "com.typesafe.akka"      %% "akka-persistence-query"       % "2.5.2",
      "com.typesafe.akka"      %% "akka-persistence-cassandra"   % "0.53",
      "io.netty"               % "netty-transport-native-epoll"  % "4.1.11.Final" classifier "linux-x86_64",
      "com.datastax.cassandra" % "cassandra-driver-core"         % "3.2.0",
      "com.typesafe.akka"      %% "akka-http"                    % "10.0.7",
      "com.trueaccord.scalapb" %% "scalapb-json4s"               % "0.3.0"
    )
  )
  .dependsOn(model % "test->test")
  .dependsOn(certgen % "test")
  .settings(libraryDependencies ++= Seq(
    "org.scalatest"     %% "scalatest"           % "3.0.3"  % Test,
    "com.typesafe.akka" %% "akka-http-testkit"   % "10.0.7" % Test,
    "com.typesafe.akka" %% "akka-stream-testkit" % "2.5.2"  % Test
  ))
  .configs(MultiJvm)
  .settings(SbtMultiJvm.multiJvmSettings)
  .dependsOn(certgen % MultiJvm)
  .settings(libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-multi-node-testkit"             % "2.5.2" % MultiJvm,
    "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % "0.53"  % MultiJvm
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
  .dependsOn(model)
  .dependsOn(wsProtocol)
  .settings(
    dependencyOverrides += "org.scala-lang.modules" %% "scala-xml" % "1.0.6",
    libraryDependencies ++= Seq(
      "com.madgag.spongycastle" % "pkix"   % "1.54.0.0",
      "com.squareup.okhttp3"    % "okhttp" % "3.8.0"
    )
  )
  .dependsOn(certgen % "test")
  .dependsOn(server % "test->test")
  .settings(libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.3" % Test)

lazy val healthcheck = project
  .in(file("healthcheck"))
  .settings(noopPublishSetting)
  .settings(
    name := "liquidity-healthcheck"
  )
  .dependsOn(client)
  .dependsOn(wsLegacyProtocol)
  .settings(
    dependencyOverrides += "org.scala-lang.modules" %% "scala-xml" % "1.0.6",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-stream-testkit" % "2.5.2",
      "org.scalatest"     %% "scalatest"           % "3.0.3"
    )
  )
  .dependsOn(certgen % "test")
  .dependsOn(server % "test->test")
  .settings(libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.3" % Test)
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
    serialization,
    model,
    persistence,
    actorProtocol,
    wsProtocol,
    wsLegacyProtocol,
    certgen,
    server,
    client,
    healthcheck,
    boardgame
  )
