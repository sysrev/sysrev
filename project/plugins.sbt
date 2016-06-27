addSbtPlugin("com.mojolly.scalate" % "xsbt-scalate-generator" % "0.5.0")

addSbtPlugin("org.scalatra.sbt" % "scalatra-sbt" % "0.5.0")

addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.1.10")

addSbtPlugin("com.typesafe.sbteclipse" % "sbteclipse-plugin" % "3.0.0")

addSbtPlugin("org.flywaydb" % "flyway-sbt" % "4.0.1")

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.3")

resolvers += "Flyway" at "https://flywaydb.org/repo"
