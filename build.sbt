name := """liquidity-common"""

organization := "com.dhpcs"

version := "0.33.0"

scalaVersion := "2.11.7"

libraryDependencies ++= Seq(
  "com.dhpcs" %% "play-json-rpc" % "0.4.0",
  "com.google.guava" % "guava" % "18.0",
  "com.google.code.findbugs" % "jsr305" % "3.0.0",
  "com.dhpcs" %% "play-json-rpc" % "0.4.0" % "test->test",
  "org.scalatest" % "scalatest_2.11" % "2.2.4" % "test"
)
