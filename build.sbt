organization := "com.dhpcs"

name := "liquidity-common"

version := "0.39.0-SNAPSHOT"

scalaVersion := "2.11.7"

libraryDependencies ++= Seq(
  "com.squareup.okio" % "okio" % "1.6.0",
  "com.dhpcs" %% "play-json-rpc" % "0.6.0",
  "org.scalatest" % "scalatest_2.11" % "2.2.4" % "test",
  "com.dhpcs" %% "play-json-rpc" % "0.6.0" % "test->test"
)
