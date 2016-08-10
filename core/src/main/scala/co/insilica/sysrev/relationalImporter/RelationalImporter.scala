package co.insilica.sysrev
package relationalImporter

import queries._

import co.insilica.sysrev.indexing.QueryEnv._
import play.api.libs.iteratee.{Enumeratee, Enumerator, Iteratee}
import doobie.imports._
import co.insilica.dataProvider.mongo.{Config => MongoConfig}

import scala.concurrent.{ExecutionContext, Future}
import scalaz.concurrent.Task

import co.insilica.dataProvider.TaskFutureOps._
import co.insilica.sysrev.Types._

import scalaz._
import Scalaz._


object RelationalImporter {

  def allArticles(implicit ec: ExecutionContext, cfg: MongoConfig) : Future[Enumerator[Article]] =
    indexing.sysrevImporter().select[Article]()

  def allKeywords(implicit ec: ExecutionContext, tx: Transactor[Task], cfg: MongoConfig): Future[Int] = {
    val kwds : Future[Set[String]] = allArticles.flatMap{ enum =>
      val ee = Enumeratee.map[Article](_.sysRev.keywords)
      enum &> ee |>>> Iteratee.fold(Set[String]()){
        case (acc, ns) => acc ++ ns
      }
    }

    kwds.map(_.toList).flatMap(insert.keywords(_).transact(tx).runFuture)
  }

  def all(implicit ec: ExecutionContext, tx: Transactor[Task], cfg : MongoConfig): Future[Unit] = {
    allArticles.flatMap(_ |>>> Iteratee.foreach[Article]{ article =>
      try {
        Queries.insertArticle(article).transact(tx).unsafePerformSync
        ()
      } catch {
        case e : Throwable => println(e)
      }
    })
  }

  /* Special purpose, first import missed authors, and didn't have document_ids.
    */
  def augmentWithDocumentIdsAndAuthors(implicit ec: ExecutionContext, tx: Transactor[Task], cfg: MongoConfig): Future[Unit] = {
    allArticles.flatMap(_ |>>> Iteratee.foreach[Article]{ article =>
      Queries.articleBodyByTitlePrefix(article.primaryTitle).flatMap{ indexArticles =>
        indexArticles.map{
          case WithAnyId(id, indexArticle) =>
            val curids = indexArticle.documentIds.getOrElse(Nil)
            val curauthors = indexArticle.authors.getOrElse(Nil)
            Queries.augmentArticleWithDocumentIdsAndAuthors(id, curids ++ article.documentIds, curauthors ++ article.authors)
        }.sequenceU
      }.transact(tx).unsafePerformSync
      ()
    })
  }
}