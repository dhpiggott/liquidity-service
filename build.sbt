import sbt.Keys._

scalaVersion in ThisBuild := "2.11.8"

lazy val commonSettings = Seq(
  scalaVersion := "2.11.8",
  organization := "com.dhpcs.liquidity",
  version := "1.2.1"
)

lazy val protocol = project.in(file("protocol"))
  .settings(commonSettings)
  .settings(Seq(
    name := "protocol",
    libraryDependencies ++= Seq(
      "com.squareup.okio" % "okio" % "1.9.0",
      "com.dhpcs" %% "play-json-rpc" % "1.1.0",
      "org.scalatest" %% "scalatest" % "2.2.6" % "test",
      "com.dhpcs" %% "play-json-rpc" % "1.1.0" % "test->test"
    )
  ))

lazy val certgen = project.in(file("certgen"))
  .settings(commonSettings)
  .settings(Seq(
    name := "certgen",
    libraryDependencies ++= Seq(
      "org.bouncycastle" % "bcpkix-jdk15on" % "1.54"
    )
  ))

lazy val server = project.in(file("server"))
  .settings(commonSettings)
  .settings(Seq(
    name := "server",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-slf4j" % "2.4.8",
      "ch.qos.logback" % "logback-classic" % "1.1.7",
      "com.typesafe.akka" %% "akka-http-experimental" % "2.4.8",
      "com.typesafe.play" %% "play-json" % "2.4.8" force(),
      "com.typesafe.akka" %% "akka-cluster-sharding" % "2.4.8",
      "com.typesafe.akka" %% "akka-persistence-cassandra" % "0.9",
      "com.typesafe.akka" %% "akka-persistence-query-experimental" % "2.4.8",
      "org.scalatest" %% "scalatest" % "2.2.6" % "test",
      "com.typesafe.akka" %% "akka-http-testkit" % "2.4.8" % "test",
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
  ))
  .dependsOn(protocol)
  .dependsOn(certgen % "test")
  .enablePlugins(JavaAppPackaging, DockerPlugin)

lazy val boardgame = project.in(file("boardgame"))
  .settings(commonSettings)
  .settings(Seq(
    name := "boardgame",
    libraryDependencies ++= Seq(
      "com.madgag.spongycastle" % "pkix" % "1.54.0.0",
      "com.squareup.okhttp3" % "okhttp-ws" % "3.4.1"
    )
  ))
  .dependsOn(protocol)
