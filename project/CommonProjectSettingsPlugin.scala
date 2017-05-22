import sbt.Keys._
import sbt._
import scoverage.ScoverageKeys._

object CommonProjectSettingsPlugin extends AutoPlugin {

  private[this] val scalaSettings = Seq(
    scalaVersion := "2.12.2",
    crossScalaVersions := Seq("2.11.11", "2.12.2"),
    scalacOptions in Compile ++= Seq(
      "-target:jvm-1.8",
      "-encoding",
      "UTF-8",
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xfuture",
      "-Xlint",
      "-Yno-adapted-args",
      "-Ywarn-dead-code",
      "-Ywarn-inaccessible",
      "-Ywarn-infer-any",
      "-Ywarn-nullary-override",
      "-Ywarn-nullary-unit",
      "-Ywarn-numeric-widen",
      "-Ywarn-unused",
      "-Ywarn-unused-import"
    )
  )

  private[this] val resolverSettings = Seq(
    resolvers += Resolver.bintrayRepo("dhpcs", "maven")
  )

  private[this] val coverageSettings = Seq(
    coverageExcludedFiles := ".*/target/.*"
  )

  private[this] val publishSettings = Seq(
    organization := "com.dhpcs"
  )

  override def trigger: PluginTrigger = allRequirements

  override lazy val projectSettings: Seq[Setting[_]] =
    scalaSettings ++
      resolverSettings ++
      coverageSettings ++
      addCommandAlias("coverage", ";clean; coverage; test; multi-jvm:test; coverageReport") ++
      addCommandAlias("validate", ";scalafmtTest; test; multi-jvm:test") ++
      publishSettings

}
