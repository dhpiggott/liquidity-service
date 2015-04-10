name := """liquidity"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.1"

libraryDependencies ++= Seq(
  cache,
  ws
)

resolvers += "Pellucid Bintray" at "http://dl.bintray.com/pellucid/maven"

libraryDependencies += "com.pellucid" %% "sealerate" % "0.0.3"