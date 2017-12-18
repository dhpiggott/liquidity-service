lazy val protobufSettings = Seq(
  PB.targets in Compile := Seq(
    scalapb.gen(flatPackage = true, singleLineToString = true) -> (sourceManaged in Compile).value
  ),
  libraryDependencies += "com.trueaccord.scalapb"          %% "scalapb-runtime" %
    com.trueaccord.scalapb.compiler.Version.scalapbVersion % ProtocPlugin.ProtobufConfig
)

lazy val `proto-binding` = project
  .in(file("proto-binding"))
  .settings(
    name := "liquidity-proto-binding"
  )
  .settings(
    libraryDependencies ++= Seq(
      "com.chuusai"   %% "shapeless" % "2.3.2",
      "org.typelevel" %% "cats-core" % "1.0.0-RC1"
    ))

lazy val model = project
  .in(file("model"))
  .settings(protobufSettings)
  .settings(
    name := "liquidity-model"
  )
  .settings(libraryDependencies += "com.squareup.okio" % "okio" % "1.13.0")

lazy val `model-proto-binding` = project
  .in(file("model-proto-binding"))
  .settings(
    name := "liquidity-model-proto-binding"
  )
  .dependsOn(`proto-binding`)
  .dependsOn(`model`)

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
  .settings(libraryDependencies += "org.typelevel" %% "cats-core" % "1.0.0-RC1")
  .dependsOn(testkit % Test)
  .settings(libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.4" % Test)

lazy val `ws-protocol-proto-binding` = project
  .in(file("ws-protocol-proto-binding"))
  .settings(
    name := "liquidity-ws-protocol-proto-binding"
  )
  .dependsOn(`proto-binding`)
  .dependsOn(`model-proto-binding`)
  .dependsOn(`ws-protocol`)

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
  .dependsOn(model)
  .settings(libraryDependencies += "com.typesafe.akka" %% "akka-typed" % "2.5.8")
  .dependsOn(`ws-protocol`)

lazy val `actor-protocol-proto-binding` = project
  .in(file("actor-protocol-proto-binding"))
  .settings(
    name := "liquidity-actor-protocol-proto-binding"
  )
  .dependsOn(`proto-binding`)
  .dependsOn(`model-proto-binding`)
  .dependsOn(`ws-protocol-proto-binding`)
  .dependsOn(`actor-protocol`)

lazy val testkit = project
  .in(file("testkit"))
  .settings(
    name := "liquidity-testkit"
  )

lazy val server = project
  .in(file("server"))
  .settings(
    name := "liquidity-server"
  )
  .dependsOn(`ws-protocol`)
  .dependsOn(`ws-protocol-proto-binding`)
  .dependsOn(`actor-protocol`)
  .dependsOn(`actor-protocol-proto-binding`)
  .settings(
    libraryDependencies ++= Seq(
      "com.typesafe.akka"      %% "akka-slf4j"                   % "2.5.8",
      "ch.qos.logback"         % "logback-classic"               % "1.2.3",
      "com.typesafe.akka"      %% "akka-cluster"                 % "2.5.8",
      "com.lightbend.akka"     %% "akka-management-cluster-http" % "0.5",
      "com.typesafe.akka"      %% "akka-cluster-sharding"        % "2.5.8",
      "com.typesafe.akka"      %% "akka-cluster-tools"           % "2.5.8",
      "com.typesafe.akka"      %% "akka-persistence"             % "2.5.8",
      "com.typesafe.akka"      %% "akka-persistence-query"       % "2.5.8",
      "com.github.dnvriend"    %% "akka-persistence-jdbc"        % "3.0.1",
      "org.tpolecat"           %% "doobie-core"                  % "0.5.0-M10",
      "org.tpolecat"           %% "doobie-hikari"                % "0.5.0-M10",
      "mysql"                  % "mysql-connector-java"          % "5.1.45",
      "com.typesafe.akka"      %% "akka-http"                    % "10.0.11",
      "com.trueaccord.scalapb" %% "scalapb-json4s"               % "0.3.3",
      "com.typesafe.play"      %% "play-json"                    % "2.6.8",
      "de.heikoseeberger"      %% "akka-http-play-json"          % "1.18.1"
    )
  )
  .dependsOn(`ws-protocol` % "test->test")
  .dependsOn(testkit % Test)
  .settings(libraryDependencies ++= Seq(
    "com.github.dnvriend" %% "akka-persistence-inmemory" % "2.5.1.1" % Test,
    "org.scalatest"       %% "scalatest"                 % "3.0.4"   % Test,
    "com.typesafe.akka"   %% "akka-http-testkit"         % "10.0.11" % Test,
    "com.typesafe.akka"   %% "akka-stream-testkit"       % "2.5.8"   % Test
  ))
  .enablePlugins(MultiJvmPlugin)
  .configs(MultiJvm)
  .settings(inConfig(MultiJvm)(scalafmtSettings))
  .dependsOn(testkit % MultiJvm)
  .settings(libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-multi-node-testkit" % "2.5.8"   % MultiJvm,
    "com.h2database"    % "h2"                       % "1.4.196" % MultiJvm
  ))
  .enablePlugins(BuildInfoPlugin, JavaAppPackaging, DockerPlugin)
  .settings(
    buildInfoPackage := "com.dhpcs.liquidity.server",
    buildInfoUsePackageAsPath := true,
    buildInfoKeys := Seq(version),
    buildInfoOptions ++= Seq(BuildInfoOption.BuildTime, BuildInfoOption.ToMap),
    packageName in Docker := "liquidity",
    version in Docker := version.value.replace('+', '-'),
    daemonUser in Docker := "root",
    dockerBaseImage := "openjdk:8-jre",
    dockerRepository := Some("837036139524.dkr.ecr.eu-west-2.amazonaws.com")
  )
