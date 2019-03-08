import sbt.Keys._
import sbt._
import sbtdynver.DynVerPlugin.autoImport._

object ProjectPlugin extends AutoPlugin {

  override def trigger: PluginTrigger = allRequirements

  override def globalSettings: Seq[Setting[_]] =
    addCommandAlias(
      "validate",
      ";scalafmtSbtCheck ;scalafmtCheck " +
        ";service/test:scalafmtCheck ;service/test " +
        ";service/it:scalafmtCheck ;service/docker:publishLocal " +
        ";service/it:testOnly *LiquidityServerComponentSpec ;service/docker:clean"
    )

  override def projectSettings: Seq[Setting[_]] =
    scalaProjectSettings ++
      dynVerSettings ++
      testProjectSettings

  // TODO: Switch to sbt-tpolecat
  private lazy val scalaProjectSettings = Seq(
    scalaVersion := "2.12.8",
    // See https://tpolecat.github.io/2017/04/25/scalac-flags.html for
    // explanations.
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
      "-Xcheckinit",
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
    )
  )

  private lazy val dynVerSettings = Seq(
    dynver in ThisBuild ~= (_.replace('+', '-')),
    version in ThisBuild ~= (_.replace('+', '-'))
  )

  private lazy val testProjectSettings = Seq(
    Test / testOptions +=
      Tests.Argument(TestFrameworks.ScalaTest, "-oD"),
    IntegrationTest / testOptions +=
      Tests.Argument(TestFrameworks.ScalaTest, "-oD")
  )

}
