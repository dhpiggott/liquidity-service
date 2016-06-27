import sbt.Keys._

scalaVersion in ThisBuild := "2.11.8"

lazy val server = project.in(file("server"))
  .settings(commonSettings)

lazy val tools = project.in(file("tools"))
  .settings(commonSettings)

lazy val commonSettings = Seq(
  scalaVersion := "2.11.8"
)
