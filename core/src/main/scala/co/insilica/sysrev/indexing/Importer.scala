package co.insilica
package sysrev
package indexing

import co.insilica.dataProvider.mongo.{Projector, InsilicaCollection}
import play.api.libs.iteratee.Enumerator
import reactivemongo.bson.{BSONDocumentReader, BSONDocumentWriter, BSONDocument}
import reactivemongo.play.iteratees.cursorProducer

import scala.annotation.implicitNotFound
import scala.concurrent.{Future, ExecutionContext}

/**
  * A QueryEnv provides all that's necessary to get Ts out of the database
 *
  * @tparam T the type of the returned objects from Mongo.
  *
  */
trait QueryEnv[T]{
  implicit def projector: Projector[T]
  implicit def reader: BSONDocumentReader[T]
  implicit def ec: ExecutionContext
}


object QueryEnv{
  /**
    * A QueryEnv can be created automatically for a given query ultimate result type, using whatever exceution context
    * is available.
    */
  @implicitNotFound("Could not find an implicit environment for type ${A}. Make sure you define an instance of Projector and BSONDocumentReader for type ${A}.  To debug this further, you can call this method explicitly in scope and see which member is unavailable.")
  implicit def queryEnv[A](implicit iprojector: Projector[A], ireader: BSONDocumentReader[A], iec: ExecutionContext) : QueryEnv[A] = new QueryEnv[A]{
    implicit def projector: Projector[A] = iprojector
    implicit def reader: BSONDocumentReader[A] = ireader
    implicit def ec: ExecutionContext = iec
  }
}

/**
  * An importer can get any kind of documents out of a single mongo collection.
  */
trait Importer {
  def collection(implicit ec: ExecutionContext): Future[InsilicaCollection]

  /**
    * Get an enumerator of all documents in the collection.
    */
  def select[T]()(implicit env: QueryEnv[T]): Future[Enumerator[T]] = select(BSONDocument())

  /**
    * With query Q, get an enumerator of documents.
    */
  def select[T, Q](q: Q)(implicit env : QueryEnv[T], qw : BSONDocumentWriter[Q]) : Future[Enumerator[T]] = {
    import env._
    collection.map(_.find(q, env.projector()).cursor[T]().enumerator())
  }
}
