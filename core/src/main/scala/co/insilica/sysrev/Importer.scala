package co.insilica
package sysrev

import co.insilica.dataProvider.mongo.bson.XmlToBsonConfig
import dataProvider.mongo.connectAsync
import dataProvider.mongo.bson.fromXml._
import reactivemongo.api.commands.WriteResult
import reactivemongo.bson.{BSONDocument, BSONArray, BSONString}
import co.insilica.dataProvider.mongo.connection.Implicits.config._

import scala.concurrent.{Future, ExecutionContext}
import scala.xml.{Node, XML}

import scalaz._
import Scalaz._

object Importer{

  /**
    * This dataset wraps a lot of text data fields with <style></style> tags.
    */
  implicit def configuration = new XmlToBsonConfig {
    def atLeastEmptyArray(n: Node) =
      if(n.child.isEmpty || n.text.isEmpty){
        BSONArray()
      } else {
        nodeWriter(this).write(n)
      }

    def knownPaths = Map(
      "style" -> (n => BSONString(n.text)),
      "contributors" -> atLeastEmptyArray,
      "urls" -> atLeastEmptyArray
    )
  }

  def docFromNode(node: Node): Option[BSONDocument] = nodeWriter write node getAs[BSONDocument] "record"

  /**
    * Import systemreview data into the mongo collection
    */
  def importData(collectionName: String)(implicit ec: ExecutionContext, ctx : SysrevConfig): Future[Option[List[WriteResult]]] = {
    import ctx._
    val o : Option[Future[List[WriteResult]]] = config.citationsURL.map { fileUrl =>
      for {
        c <- connectAsync(collectionName)
        ds <- Future((XML.load(fileUrl) \\ "record").toList)
        r <- ds.flatMap(docFromNode(_) map (s => List(c.insert(s))) getOrElse Nil).sequenceU
      } yield r
    }

    o.sequenceU
  }

}