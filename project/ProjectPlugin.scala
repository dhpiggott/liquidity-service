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
      testProjectSettings

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
