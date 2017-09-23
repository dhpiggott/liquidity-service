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
    scalaVersion := "2.12.3",
    // See https://tpolecat.github.io/2017/04/25/scalac-flags.html for explanations.
    scalacOptions ++= Seq(
      "-deprecation",
      "-encoding",
      "utf-8",
      "-explaintypes",
      "-feature",
      "-language:existentials",
      "-language:experimental.macros",
      "-language:higherKinds",
      "-language:implicitConversions",
      "-unchecked",
      // TODO: Re-enable this when Scala 2.12.4 is released (see https://github.com/scala/scala/pull/6024).
      // "-Xcheckinit",
      "-Xfatal-warnings",
      "-Xfuture",
      "-Xlint:adapted-args",
      "-Xlint:by-name-right-associative",
      "-Xlint:constant",
      "-Xlint:delayedinit-select",
      "-Xlint:doc-detached",
      "-Xlint:inaccessible",
      "-Xlint:infer-any",
      "-Xlint:missing-interpolator",
      "-Xlint:nullary-override",
      "-Xlint:nullary-unit",
      "-Xlint:option-implicit",
      "-Xlint:package-object-classes",
      "-Xlint:poly-implicit-overload",
      "-Xlint:private-shadow",
      "-Xlint:stars-align",
      "-Xlint:type-parameter-shadow",
      "-Xlint:unsound-match",
      "-Yno-adapted-args",
      "-Ypartial-unification",
      "-Ywarn-dead-code",
      "-Ywarn-extra-implicit",
      "-Ywarn-inaccessible",
      "-Ywarn-infer-any",
      "-Ywarn-nullary-override",
      "-Ywarn-nullary-unit",
      "-Ywarn-numeric-widen",
      "-Ywarn-unused:implicits",
      "-Ywarn-unused:imports",
      "-Ywarn-unused:locals",
      "-Ywarn-unused:params",
      "-Ywarn-unused:patvars",
      "-Ywarn-unused:privates",
      "-Ywarn-value-discard"
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
