organization := "com.dhpcs"

name := "liquidity-common"

version := "1.0.0"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "com.squareup.okio" % "okio" % "1.6.0",
  "com.dhpcs" %% "play-json-rpc" % "1.0.0",
  "org.scalatest" %% "scalatest" % "2.2.6" % "test",
  "com.dhpcs" %% "play-json-rpc" % "1.0.0" % "test->test"
)
