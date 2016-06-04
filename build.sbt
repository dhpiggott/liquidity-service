organization := "com.dhpcs"

name := "liquidity-boardgame"

version := "1.0.0-SNAPSHOT"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "com.madgag.spongycastle" % "pkix" % "1.54.0.0",
  "com.squareup.okhttp3" % "okhttp-ws" % "3.2.0",
  "com.dhpcs" %% "liquidity-common" % "1.0.0"
)
