name := "sysrev"

version := "0.0.1-SNAPSHOT"

val Organization = "co.insilica"
val ScalaVersion = "2.11.8"
val scalatraVersion = "2.4.1"
val doobieVersion = "0.3.0"
val scalazVersion = "7.2.4"
val reactiveMongoVersion = "0.11.14"

val scalatraDeps = Seq(
  // Scalatra:
  "org.scalatra" %% "scalatra" % scalatraVersion,
  "org.scalatra" %% "scalatra-scalate" % scalatraVersion,
  "org.scalatra" %% "scalatra-scalatest" % scalatraVersion,
  "org.json4s"   %% "json4s-jackson" % "3.4.0",
  "org.scalatra" %% "scalatra-json" % scalatraVersion,
  "org.scalatra" %% "scalatra-auth" % scalatraVersion,
  "org.scalatra" %% "scalatra-test" % scalatraVersion,
  "javax.servlet" % "javax.servlet-api" % "3.1.0" % "provided",
  "org.scalatra" %% "scalatra-scalatest" % scalatraVersion,

  // Scala library and reflect, needed by scalate now...
  "org.scala-lang" % "scala-reflect" % ScalaVersion,
  "org.scala-lang" % "scala-compiler" % ScalaVersion
)

val postgresDeps = Seq(
  "org.postgresql" % "postgresql" % "9.4.1208",
  "org.tpolecat" %% "doobie-core"               % doobieVersion,
  "org.tpolecat" %% "doobie-contrib-postgresql" % doobieVersion,
  "co.insilica" %% "doobie-contrib-scalatest" % "0.1.1"
)

lazy val scalaSettings = Seq(
  scalacOptions ++= Seq(
    "-encoding", "UTF-8", // 2 args
    "-feature",
    "-unchecked",
    "-Xlint",
    "-Yno-adapted-args",
    "-Ywarn-dead-code",
    "-Ywarn-value-discard"
  )
)

def keyFile = new java.io.File(s"${sys.env("HOME")}/.ssh/insilica")

val insilicaResolver = {
  val pList = List(Resolver.localBasePattern)
  implicit val patterns = Patterns(
    Nil,
    Resolver.mavenStyleBasePattern :: Nil,
    isMavenCompatible = true,
    descriptorOptional = true,
    skipConsistencyCheck = true
  )
  Resolver.sftp("Insilica repo", "insilica.co", "/data/maven/").as("maven", keyFile)
}


lazy val buildSettings = Seq(
  organization := Organization,
  scalaVersion := ScalaVersion,
  resolvers ++= Seq(
    insilicaResolver,
    "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases",
    Resolver.sonatypeRepo("snapshots"),
    Classpaths.typesafeReleases,
    "Sonatype Nexus Releases" at "https://oss.sonatype.org/content/repositories/releases/",
    "Sonatype Nexus Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/",
    "Netbeans" at "http://bits.netbeans.org/maven2/"
  )
)

val commonDependencySettings = Seq(
  libraryDependencies ++= Seq(
  // Json
  "org.json4s"   %% "json4s-jackson" % "3.3.0",

  // test
  "org.scalatest" %% "scalatest" % "3.0.0-RC3" % "test",
  "org.scala-lang.modules" %% "scala-xml" % "1.0.5",

  // files
  "com.lihaoyi" %% "upickle" % "0.3.9",

  // Mongo
  "org.reactivemongo" %% "reactivemongo" % reactiveMongoVersion,
  "org.reactivemongo" %% "reactivemongo-iteratees" % reactiveMongoVersion,

  // Doobie
  "org.tpolecat" %% "doobie-core"               % doobieVersion,
  "org.tpolecat" %% "doobie-contrib-postgresql" % doobieVersion,
  "com.typesafe.play" %% "play-iteratees" % "2.4.6",
  "org.scalaz" %% "scalaz-core" % scalazVersion,

  // Insilica internal projects:
  "co.insilica" %% "doobie-contrib-scalatest" % "0.1.1",
  "co.insilica" %% "data-provider" % "0.3.3",

  "org.scala-lang.modules" % "scala-parser-combinators_2.11" % "1.0.4"
))

val commonSettings = buildSettings ++ scalaSettings ++ commonDependencySettings

val apiSettings = Seq(
  libraryDependencies ++= scalatraDeps ++ postgresDeps ++ Seq(
    "co.insilica" %% "api-stack" % "0.1.4",
    "co.insilica" %% "auth" % "0.1.7"
  )
)

val sparkSettings = Seq(
  libraryDependencies ++= Seq(
    // Spark Machine Learning
    "org.apache.spark" % "spark-core_2.11" % "1.6.2",
    "org.apache.spark" % "spark-sql_2.11" % "1.6.2",
    "org.apache.spark" % "spark-mllib_2.11" % "1.6.2"
  ),
  dependencyOverrides += "com.fasterxml.jackson.core" % "jackson-databind" % "2.4.4"
)

val coreDataSettings = Seq(
  libraryDependencies ++= Seq(
    "com.opencsv" % "opencsv" % "3.5",

    // Breeze matrix ops
    "org.scalanlp" %% "breeze" % "0.12",
    // native libraries are not included by default. add this if you want them (as of 0.7)
    // native libraries greatly improve performance, but increase jar sizes.
    // It also packages various blas implementations, which have licenses that may or may not
    // be compatible with the Apache License. No GPL code, as best I know.
    "org.scalanlp" %% "breeze-natives" % "0.12",
    // the visualization library is distributed separately as well.
    // It depends on LGPL code.
    "org.scalanlp" %% "breeze-viz" % "0.12"
  )
)

lazy val core = project.in(file("./core"))
  .settings(commonSettings ++ sparkSettings ++ coreDataSettings)
  .settings(name := "core")
  .settings(description := "")
  .settings(jarName in assembly := "sysrev-fingerprint_assembly.jar")
  .settings(assemblyMergeStrategy in assembly := {
    case PathList("reference.conf") => MergeStrategy.concat
    case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
    case m if m.toLowerCase.matches("meta-inf.*\\.sf$") => MergeStrategy.discard
    case _ => MergeStrategy.first
  })
  .settings(fork in run := true)
  .settings(assemblyOption in assembly :=
    (assemblyOption in assembly).value.copy(includeScala = false))
  .settings(mainClass in assembly := Some("co.insilica.sysrev.indexing.Test"))
  .settings(test in assembly := {})

lazy val sysrevApi = project.in(file("./api"))
  .settings(commonSettings ++ apiSettings)
  .settings(name := "api")
  .enablePlugins(JettyPlugin)
  .settings(containerPort := 3020)
  .settings(javaOptions := Seq("-Xmx768m"))
  .dependsOn(core)
  .aggregate(core)
