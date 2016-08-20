import sbt.Keys._

scalaVersion in ThisBuild := "2.11.8"

lazy val commonSettings = organization := "com.dhpcs"

lazy val playJsonRpc = "com.dhpcs" %% "play-json-rpc" % "1.1.1"

lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.0.0"

lazy val liquidityProtocol = project.in(file("protocol"))
  .settings(commonSettings)
  .settings(
    name := "liquidity-protocol",
    libraryDependencies ++= Seq(
      "com.squareup.okio" % "okio" % "1.9.0",
      playJsonRpc,
      scalaTest,
      playJsonRpc % "test->test"
    )
  )

lazy val liquidityServer = project.in(file("server"))
  .settings(commonSettings)
  .settings(
    name := "liquidity-server",
    publishArtifact := false,
    publish := {},
    publishLocal := {},
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-slf4j" % "2.4.9",
      "ch.qos.logback" % "logback-classic" % "1.1.7",
      "com.typesafe.akka" %% "akka-http-experimental" % "2.4.9",
      "com.typesafe.play" %% "play-json" % "2.4.8" force(),
      "com.typesafe.akka" %% "akka-cluster-sharding" % "2.4.9",
      "com.typesafe.akka" %% "akka-persistence-cassandra" % "0.9",
      "com.typesafe.akka" %% "akka-persistence-query-experimental" % "2.4.9",
      scalaTest % "test",
      "com.typesafe.akka" %% "akka-http-testkit" % "2.4.9" % "test",
      "org.apache.cassandra" % "cassandra-all" % "3.7" % "test"
    ),
    parallelExecution in Test := false,
    testGrouping in Test <<= (definedTests in Test).map(_.map(test =>
      Tests.Group(
        name = test.name,
        tests = Seq(test),
        runPolicy = Tests.SubProcess(ForkOptions(runJVMOptions = Seq("-Xms512M", "-Xmx1G"))))
    )),
    dockerBaseImage := "java:8-jre",
    dockerExposedPorts := Seq(443),
    daemonUser in Docker := "root",
    bashScriptExtraDefines += "addJava -Djdk.tls.ephemeralDHKeySize=2048"
  )
  .dependsOn(liquidityProtocol)
  .dependsOn(liquidityCertgen % "test")
  .enablePlugins(JavaAppPackaging, DockerPlugin)

lazy val liquidityCertgen = project.in(file("certgen"))
  .settings(commonSettings)
  .settings(
    name := "liquidity-certgen",
    publishArtifact := false,
    publish := {},
    publishLocal := {},
    libraryDependencies ++= Seq(
      "org.bouncycastle" % "bcpkix-jdk15on" % "1.54"
    )
  )

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

lazy val root = project.in(file("."))
  .settings(commonSettings)
  .settings(
    name := "liquidity-root",
    publishArtifact := false,
    publish := {},
    publishLocal := {}
  )
  .aggregate(
    liquidityProtocol,
    liquidityServer,
    liquidityCertgen,
    liquidityBoardgame
  )
