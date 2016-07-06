import Common._

name := "sysrev"

version := "0.0.1-SNAPSHOT"

libraryDependencies ++= scalatraDeps ++ postgresDeps ++ mongoDeps ++ solrDeps ++ Seq(
  "co.insilica" %% "data-provider" % "0.3.2-SNAPSHOT",
  "org.scala-lang.modules" %% "scala-xml" % "1.0.5",
  "com.lihaoyi" %% "upickle" % "0.3.9"
)


lazy val root = project in file(".") settings (Common.settings)

