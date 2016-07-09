package co.insilica.sysrev
package relationalImporter

import Types._
import co.insilica.sysrev.Types._
import co.insilica.sysrev.indexing.{QueryEnv, DocIndex}
import co.insilica.sysrev.indexing.QueryEnv._
import play.api.libs.iteratee.{Enumeratee, Enumerator, Iteratee}
import doobie.imports._

import scala.concurrent.{ExecutionContext, Future}
import scalaz.concurrent.Task

import co.insilica.dataProvider.TaskFutureOps._

import scalaz._
import Scalaz._


object RelationalImporter {
  def allKeywords(implicit ec: ExecutionContext, tx: Transactor[Task]): Future[Int] = {
    val articles : Future[Enumerator[Article]] = indexing.sysrevImporter.select[Article]()
    val kwds : Future[Set[String]] = articles.flatMap{ enum =>
      val ee = Enumeratee.map[Article](_.sysRev.keywords)
      enum &> ee |>>> Iteratee.fold(Set[String]()){
        case (acc, ns) => acc ++ ns
      }
    }

    kwds.map(_.toList).flatMap(Queries.insert.keywords(_).transact(tx).runFuture)
  }

  def all(implicit ec: ExecutionContext, tx: Transactor[Task]): Future[Unit] = {
    val articles: Future[Enumerator[Article]] = indexing.sysrevImporter.select[Article]()
    articles.flatMap(_ |>>> Iteratee.foreach[Article]{ article =>
      val res: Int = Queries.insertArticle(article).transact(tx).run
      println(res)
    })
  }
}