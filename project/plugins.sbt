addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.3")
addSbtPlugin("com.geirsson" % "sbt-scalafmt" % "1.5.1")

libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.8.4"
addSbtPlugin("com.thesamet" % "sbt-protoc" % "0.99.20")

addSbtPlugin("com.dwijnand" % "sbt-dynver" % "3.3.0")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.9.0")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.5.1")
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.21")
addSbtPlugin("ch.epfl.scala" % "sbt-release-early" % "2.1.1")
