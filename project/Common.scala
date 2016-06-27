import sbt.Tests.Setup
import sbt._
import sbt.SettingKey._
import Keys._
import org.scalatra.sbt._
import org.scalatra.sbt.PluginKeys._
import com.earldouglas.xwp.JettyPlugin
import com.mojolly.scalate.ScalatePlugin._
import ScalateKeys._
import com.typesafe.sbteclipse.plugin.EclipsePlugin._

object Common {
  val Organization = "co.insilica"
  val ScalaVersion = "2.11.8"
  val scalatraVersion = "2.4.1"
  val doobieVersion = "0.2.3"

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

  val mongoDeps = Seq(
    "org.reactivemongo" %% "reactivemongo" % "0.11.11",
    "com.typesafe.play" %% "play-iteratees" % "2.4.6"
  )

  val solrDeps = Seq(
    "io.netty" % "netty" % "3.9.2.Final" force(),
    "io.ino" %% "solrs" % "1.3.4"
  )

  testOptions += Setup( cl =>
    cl.loadClass("org.slf4j.LoggerFactory").
      getMethod("getLogger",cl.loadClass("java.lang.String")).
      invoke(null,"ROOT")
  )

  val settings =
    Seq(
      scalaVersion := ScalaVersion,
      organization := Organization,
      scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"),
      scalacOptions in Test ++= Seq("-Yrangepos"),
      fork := true,
      fork in run := true,
      fork in test := true,
      resolvers ++= Seq(
        Resolver.sonatypeRepo("snapshots"),
        Classpaths.typesafeReleases,
        "Sonatype Nexus Releases" at "https://oss.sonatype.org/content/repositories/releases/",
        "Sonatype Nexus Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/",
        "Netbeans" at "http://bits.netbeans.org/maven2/",
        "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases",
        insilicaResolver
      ),
      libraryDependencies ++= Seq(
        "org.scalatest" %% "scalatest" % "3.0.0-M15",
        "com.typesafe.play" %% "play-json" % "2.4.6",
        "ch.qos.logback" % "logback-classic" % "1.1.1" % "runtime"
      ),
      watchSources ~= {
        _ filter (_.getName endsWith ".scala")
      },
      updateOptions := updateOptions.value.withCachedResolution(true),
      publishTo := Some(insilicaResolver),
      resourceDirectory in Compile := baseDirectory.value / "resources",
      addCompilerPlugin("org.psywerx.hairyfotr" %% "linter" % "0.1.14")
    )
}
