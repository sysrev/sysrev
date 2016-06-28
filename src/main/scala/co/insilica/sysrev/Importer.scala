package co.insilica
package sysrev

import Implicits._
import co.insilica.dataProvider.mongo.bson.XmlToBsonConfig
import dataProvider.mongo.{InsilicaCollection, connect}
import dataProvider.mongo.connection.Implicits.config._
import dataProvider.mongo.bson.fromXml._
import reactivemongo.api.commands.WriteResult
import reactivemongo.bson.{BSONString, BSONValue}

import scala.concurrent.{Future, ExecutionContext}
import scala.xml.{Elem, Node, NodeSeq, XML}

import scalaz._
import Scalaz._

object Importer{

  /**
    * This dataset wraps a lot of text data fields with <style></style> tags.
    */
  implicit def configuration = new XmlToBsonConfig {
    override def knownPaths: Map[String, (Node) => BSONValue] = Map(
      "style" -> (n => BSONString(n.text))
    )
  }


  /**
    * Import systemreview data into the mongo collection
    */
  def importData(implicit ec: ExecutionContext): Future[Option[List[WriteResult]]] = {
    val o : Option[Future[List[WriteResult]]] = config.citationsURL.map { fileUrl =>
      for {
        c <- connect("sysrev")
        ds <- Future((XML.load(fileUrl) \\ "record").toList)
        r <- ds.map(c.insert(_: Node)).sequenceU
      } yield r
    }

    o.sequenceU
  }

}