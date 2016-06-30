import sbt.Keys._

scalaVersion in ThisBuild := "2.11.8"

lazy val certgen = project.in(file("certgen"))
  .settings(commonSettings)

lazy val protocol = project.in(file("protocol"))
  .settings(commonSettings)

lazy val server = project.in(file("server"))
  .settings(commonSettings)
  .dependsOn(protocol)

lazy val boardgame = project.in(file("boardgame"))
  .settings(commonSettings)
  .dependsOn(protocol)

lazy val commonSettings = Seq(
  scalaVersion := "2.11.8"
)
