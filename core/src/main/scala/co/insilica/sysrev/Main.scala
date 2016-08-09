package co.insilica.sysrev

import scala.concurrent.{Future, Await}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scalaz._
import Scalaz._


object XmlImporter extends App{
  import SysrevConfig.default

  println("Running with config")
  println(default.config)

  Await.result(Importer.importData("Citations"), Duration.Inf)
}
