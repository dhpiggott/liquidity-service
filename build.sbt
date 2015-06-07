name := "liquidity-common"

organization := "com.dhpcs"

version := "0.1.0"

scalaVersion := "2.11.6"

resolvers += "Pellucid Bintray" at "https://dl.bintray.com/pellucid/maven"

libraryDependencies ++= Seq(
  "com.pellucid" %% "sealerate" % "0.0.3",
  "com.typesafe.play" %% "play-json" % "2.3.8",
  "commons-codec" % "commons-codec" % "1.10"
)
