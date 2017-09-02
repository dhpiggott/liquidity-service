scalafmtVersion := "1.2.0"
addSbtPlugin("com.lucidchart" % "sbt-scalafmt" % "1.10")

libraryDependencies += "com.trueaccord.scalapb" %% "compilerplugin" % "0.6.2"
addSbtPlugin("com.thesamet" % "sbt-protoc" % "0.99.11")

addSbtPlugin("com.dwijnand" % "sbt-dynver" % "2.0.0")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.7.0")
addSbtPlugin("com.typesafe.sbt" % "sbt-multi-jvm" % "0.4.0")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.5.1")
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.2.2")
