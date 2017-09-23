import com.lucidchart.sbt.scalafmt.ScalafmtCorePlugin
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys._
import sbt.Keys._
import sbt._
import scoverage.ScoverageKeys._

object CommonSettingsPlugin extends AutoPlugin {

  override def trigger: PluginTrigger = allRequirements

  override def globalSettings: Seq[Setting[_]] =
    addCommandAlias(
      "validate",
      ";reload plugins; sbt:scalafmt::test; scalafmt::test; reload return; " +
        "sbt:scalafmt::test; scalafmt::test; test:scalafmt::test; multi-jvm:scalafmt::test; test; multi-jvm:test"
    )

  override def buildSettings: Seq[Setting[_]] =
    resolverBuildSettings ++
      scalaBuildSettings ++
      scalafmtBuildSettings ++
      testBuildSettings ++
      coverageBuildSettings ++
      publishBuildSettings

  private lazy val resolverBuildSettings = Seq(
    resolvers += Resolver.bintrayRepo("dhpcs", "maven"),
    conflictManager := ConflictManager.strict
  )

  private lazy val scalafmtBuildSettings = Seq(
    ScalafmtCorePlugin.autoImport.scalafmtVersion := "1.2.0"
  )

  private lazy val scalaBuildSettings = Seq(
    scalaVersion := "2.11.11",
    scalacOptions ++= Seq(
      "-encoding",
      "utf-8"
    ),
    dependencyOverrides ++= Seq(
      "org.scala-lang" % "scala-compiler" % scalaVersion.value,
      "org.scala-lang" % "scala-library" % scalaVersion.value,
      "org.scala-lang" % "scala-reflect" % scalaVersion.value,
      "org.scala-lang" % "scalap" % scalaVersion.value
    )
  )

  private lazy val testBuildSettings = Seq(
    testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-oDF"),
    testOptions in MultiJvm += Tests.Argument(TestFrameworks.ScalaTest, "-oDF")
  )

  private lazy val coverageBuildSettings = Seq(
    coverageExcludedFiles := ".*/target/.*"
  )

  private lazy val publishBuildSettings = Seq(
    organization := "com.dhpcs"
  )

}
