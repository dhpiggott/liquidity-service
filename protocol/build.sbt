name := "liquidity-protocol"

version := "1.0-SNAPSHOT"

libraryDependencies ++= Seq(
  "com.squareup.okio" % "okio" % "1.8.0",
  "com.dhpcs" %% "play-json-rpc" % "1.0.1",
  "org.scalatest" %% "scalatest" % "2.2.6" % "test",
  "com.dhpcs" %% "play-json-rpc" % "1.0.1" % "test->test"
)
