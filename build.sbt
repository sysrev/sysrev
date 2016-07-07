name := "sysrev"

version := "0.0.1-SNAPSHOT"

val Organization = "co.insilica"
val ScalaVersion = "2.11.8"
val scalatraVersion = "2.4.1"
val doobieVersion = "0.2.3"

val scalatraDeps = Seq(
  // Scalatra:
  "org.scalatra" %% "scalatra" % scalatraVersion,
  "org.scalatra" %% "scalatra-scalate" % scalatraVersion,
  "org.scalatra" %% "scalatra-scalatest" % scalatraVersion,
  "org.json4s"   %% "json4s-jackson" % "3.3.0",
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
  "co.insilica" %% "doobie-contrib-scalatest" % "0.1.1-SNAPSHOT"
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
  // akka
  "org.json4s"   %% "json4s-jackson" % "3.3.0",
  // test
  "org.scalatest" %% "scalatest" % "3.0.0-RC3" % "test",
  "co.insilica" %% "data-provider" % "0.3.2-SNAPSHOT",
  "org.scala-lang.modules" %% "scala-xml" % "1.0.5",
  "com.lihaoyi" %% "upickle" % "0.3.9",
  "org.reactivemongo" %% "reactivemongo" % "0.11.11"
))

val commonSettings = buildSettings ++ scalaSettings ++ commonDependencySettings

val apiSettings = Seq(
  libraryDependencies ++= scalatraDeps ++ postgresDeps ++ Seq(
    "co.insilica" %% "api-stack" % "0.1.2-SNAPSHOT",
    "co.insilica" %% "auth" % "0.1.5-SNAPSHOT"
  )
)

lazy val core = project.in(file("./core"))
  .settings(commonSettings)
  .settings(name := "core")
  .settings(description := "")

lazy val sysrevApi = project.in(file("./api"))
  .settings(commonSettings ++ apiSettings)
  .settings(name := "api")
  .enablePlugins(JettyPlugin)
  .settings(containerPort := 3005)
  .dependsOn(core)
  .aggregate(core)
