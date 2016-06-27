package co.insilica
package sysrev

import Implicits._
import dataProvider.mongo.{InsilicaCollection, connect}
import dataProvider.mongo.connection.Implicits.config._
import dataProvider.mongo.bson.fromXml._
import reactivemongo.api.commands.WriteResult

import scala.concurrent.{Future, ExecutionContext}
import scala.xml.{Elem, Node, NodeSeq, XML}

import scalaz._
import Scalaz._

object Importer{


  def importData(implicit ec: ExecutionContext): Future[Option[List[WriteResult]]] = {
    val collection: Future[InsilicaCollection] = connect("sysrev")

    val o : Option[Future[List[WriteResult]]] = config.citationsURL.map { data =>

      val xml: Future[Elem] = Future(XML.load(data))

      val docs: Future[List[Node]] = xml.map(x => (x \\ "record").toList)

      // TODO: Trying to insert the entire document at once, doesn't work.
      // Need to chunk the records up.
      val res : Future[List[WriteResult]] = for {
        c <- collection
        ds <- docs
        r <- ds.map(c.insert(_: Node)).sequenceU
      } yield r

      res
    }

    o.sequenceU
  }

}