package co.insilica
package sysrev
package indexing

import co.insilica.dataProvider.mongo.{Projector, InsilicaCollection}
import play.api.libs.iteratee.Enumerator
import reactivemongo.bson.{BSONDocumentReader, BSONDocumentWriter, BSONDocument}

import scala.concurrent.{Future, ExecutionContext}

trait QueryEnv[T]{
  implicit def projector: Projector[T]
  implicit def reader: BSONDocumentReader[T]
  implicit def ec: ExecutionContext
}

object QueryEnv{
  implicit def queryEnv[A](implicit iprojector: Projector[A], ireader: BSONDocumentReader[A], iec: ExecutionContext) : QueryEnv[A] = new QueryEnv[A]{
    implicit def projector: Projector[A] = iprojector
    implicit def reader: BSONDocumentReader[A] = ireader
    implicit def ec: ExecutionContext = iec
  }

}

trait Importer {
  def collection(implicit ec: ExecutionContext): Future[InsilicaCollection]

  def select[T](q: BSONDocument = BSONDocument())(implicit env : QueryEnv[T]) : Future[Enumerator[T]] = {
    import env._
    collection.map(_.find(q, env.projector()).cursor[T]().enumerate())
  }
}