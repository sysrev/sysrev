package co.insilica
package sysrev
package indexing

import Implicits._

import co.insilica.dataProvider.mongo.connection.Implicits.config._
import dataProvider.mongo.Projector
import dataProvider.mongo.connectAsync
import play.api.libs.iteratee.{Enumeratee, Iteratee}
import reactivemongo.bson.{BSONDocument, BSONDocumentReader}

import scala.concurrent.{Future, ExecutionContext}

object DocIndex {
  case class Titles(title: String, secondaryTitle: Option[String])
  case class SysRev(titles: Titles, docabstract: Option[String], keywords: List[String])

  implicit object SysRevProjector extends Projector[SysRev] {
    def apply(): BSONDocument = BSONDocument("abstract" -> 1, "keywords" -> 1, "titles" -> 1)
  }

  implicit val titleReader = new BSONDocumentReader[Titles] {
    override def read(bson: BSONDocument): Titles = Titles(
      bson.getAs[String]("title").get,
      bson.getAs[String]("secondary-title")
    )
  }

  implicit object SysRevReader extends BSONDocumentReader[SysRev]{
    override def read(bson: BSONDocument): SysRev = SysRev(
      bson.getAs[Titles]("titles").get,
      bson.getAs[String]("abstract"),
      bson.getAs[BSONDocument]("keywords") flatMap (_.getAs[List[String]]("keyword")) getOrElse (List[String]())
    )
  }

  object sysrevImporter extends Importer{
    def collection(implicit ec: ExecutionContext) = connectAsync("sysrev")
  }

  def index()(implicit ec: ExecutionContext) : Future[Seq[SysRev]] = {
    sysrevImporter.select[SysRev]().flatMap { enum =>
      enum |>>> Iteratee.takeUpTo(5)
    }
  }
}
