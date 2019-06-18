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
    dynVerSettings ++
      scalaProjectSettings ++
      testProjectSettings

  private lazy val scalaProjectSettings = Seq(
    scalaVersion := "2.12.8"
  )

  private lazy val dynVerSettings = Seq(
    dynverSeparator in ThisBuild := "-"
  )

  private lazy val testProjectSettings = Seq(
    Test / testOptions +=
      Tests.Argument(TestFrameworks.ScalaTest, "-oD"),
    IntegrationTest / testOptions +=
      Tests.Argument(TestFrameworks.ScalaTest, "-oD")
  )

}
