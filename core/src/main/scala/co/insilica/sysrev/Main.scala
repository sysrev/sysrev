package co.insilica.sysrev

import co.insilica.sysrev.relationalImporter.RelationalImporter
import doobie.imports.Transactor

import scala.concurrent.{Future, Await}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scalaz._
import Scalaz._
import scalaz.concurrent.Task



object XmlImporter extends App{
  Await.result(Importer.importData("Citations"), Duration.Inf)
}


object RelationalImporterMain extends App{
  import SysrevConfig.default
  implicit val tx = default.transactor
  implicit val cfg = default.config.mongo

  println("Starting with config")
  println(default.config)


  println("Applying destructive database operations in 5 seconds")
  Thread.sleep(5000)

  Await.result(RelationalImporter.all, Duration.Inf)
}

object DocumentIdsUpdateMain extends App {
  import SysrevConfig.default
  implicit val tx = default.transactor
  implicit val cfg = default.config.mongo

  println("Starting with config")
  println(default.config)

  println("Applying destructive database operations in 5 seconds")
  Thread.sleep(5000)

  Await.result(RelationalImporter.augmentWithDocumentIdsAndAuthors, Duration.Inf)

}