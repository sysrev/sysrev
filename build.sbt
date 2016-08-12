name := "sysrev"

version := "0.0.1-SNAPSHOT"

val Organization = "co.insilica"
val sv = "2.11.8"
val scalatraVersion = "2.4.1"
val doobieVersion = "0.3.0"
val scalazVersion = "7.2.0"   // matched with doobieversion
val reactiveMongoVersion = "0.11.14"

scalaVersion := sv

val scalatraDeps = Seq(
  // Scalatra:
  "org.scalatra" %% "scalatra" % scalatraVersion,
  "org.scalatra" %% "scalatra-scalate" % scalatraVersion,
  "org.scalatra" %% "scalatra-scalatest" % scalatraVersion,
  "org.json4s"   %% "json4s-jackson" % "3.4.0",
  "org.scalatra" %% "scalatra-json" % scalatraVersion,
  "org.scalatra" %% "scalatra-auth" % scalatraVersion,
  "org.scalatra" %% "scalatra-test" % scalatraVersion,
//  "javax.servlet" % "javax.servlet-api" % "3.1.0",
  "org.scalatra" %% "scalatra-scalatest" % scalatraVersion,

  // Scala library and reflect, needed by scalate now...
  "org.scala-lang" % "scala-reflect" % sv,
  "org.scala-lang" % "scala-compiler" % sv
)

val postgresDeps = Seq(
  "org.postgresql" % "postgresql" % "9.4-1201-jdbc41",    // tied to doobie version
  "org.tpolecat" %% "doobie-core"               % doobieVersion,
  "org.tpolecat" %% "doobie-contrib-postgresql" % doobieVersion,
  "co.insilica" %% "doobie-contrib-scalatest" % "0.1.1"
)

lazy val scalaSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-encoding", "UTF-8", // 2 args
    "-feature",
    "-target:jvm-1.8",
    "-unchecked",
    "-Xlint",
    "-Xfuture",
    "-Yno-adapted-args",
    "-Ywarn-dead-code",
    "-Ywarn-numeric-widen",
    "-Ywarn-unused",
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
  scalaVersion := sv,
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
  "org.json4s"   %% "json4s-jackson" % "3.4.0",

  // test
  "org.scalatest" %% "scalatest" % "3.0.0" % "test",
  "org.scala-lang.modules" %% "scala-xml" % "1.0.5",

  // files
  "com.lihaoyi" %% "upickle" % "0.4.1",
  "com.opencsv" % "opencsv" % "3.8",
  
  // Mongo
  "org.reactivemongo" %% "reactivemongo" % reactiveMongoVersion,
  "org.reactivemongo" %% "reactivemongo-iteratees" % reactiveMongoVersion,

  // Doobie
  "org.tpolecat" %% "doobie-core"               % doobieVersion,
  "org.tpolecat" %% "doobie-contrib-postgresql" % doobieVersion,

  "com.typesafe.play" %% "play-iteratees" % "2.3.10", //reactivemongo version uses this.
  "org.scalaz" %% "scalaz-core" % scalazVersion,

  // Insilica internal projects:
  "co.insilica" %% "doobie-contrib-scalatest" % "0.1.1",
  "co.insilica" %% "data-provider" % "0.3.3",

  // Auth requirement:
  "de.svenkubiak" % "jBCrypt" % "0.4.1",

  "org.scala-lang.modules" % "scala-parser-combinators_2.11" % "1.0.4"
))

val commonSettings = buildSettings ++ scalaSettings ++ commonDependencySettings

val apiSettings = Seq(
  libraryDependencies ++= scalatraDeps ++ postgresDeps ++ Seq(
    "co.insilica" %% "api-stack" % "0.2.0",
    "co.insilica" %% "auth" % "0.2.0"
  )
)

lazy val core = project.in(file("./core"))
  .settings(commonSettings)
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
