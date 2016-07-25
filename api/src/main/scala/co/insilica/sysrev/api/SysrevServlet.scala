package co.insilica.sysrev
package api

import co.insilica.apistack.{ApiStack, ResultWrapSupport}
import co.insilica.auth.Types.WithAnyId
import co.insilica.sysrev.indexing.{QueryEnv, DocIndex}
import co.insilica.sysrev.relationalImporter.Types.{CriteriaId, WithArticleId, ArticleId, WithCriteriaId}
import co.insilica.sysrev.relationalImporter._
import doobie.free.connection.ConnectionOp

import co.insilica.dataProvider.TaskFutureOps._

import org.scalatra._
import doobie.imports._

import scala.concurrent.Future
import scalaz._
import Scalaz._
import scalaz.concurrent.Task

case class ArticleIds(articleIds: List[ArticleId])
case class ErrorResult(msg: String)

class SysrevServlet extends ApiStack with FutureSupport with ResultWrapSupport {

  implicit val tx: Transactor[Task] = Implicits.transactor

  def getRankedPage(p: Int = 0) : Task[Map[ArticleId, WithScore[ArticleWithoutKeywords]]] =
    Queries.rankedArticlesPage(p).transact(tx).map{ xs =>
      xs.map(WithArticleId.unapply(_).get).toMap
    }

  getT("/ranking/:page")(params("page").parseInt.toOption.map(getRankedPage).getOrElse(getRankedPage(0)))

  getT("/ranking")(getRankedPage(0))

  getT("/criteria")(Queries.allCriteria().transact(tx).map(_.map(WithCriteriaId.unapply(_).get).toMap))

  getT("/search/:text")(Queries.textSearch(params("text")).transact(tx))

  /**
    * Returns a map articleid -> List[criteriaid, boolean]
    */
  getT("/allcriteria"){
    for {
      criteria <- Queries.allCriteriaResponses.transact(tx)
      articles <- Queries.articlesById(criteria.keys.toList).transact(tx)
    } yield {
      Map(
        "criteria" -> criteria,
        "articles" -> articles.map(WithArticleId.unapply(_).get).toMap
      )
    }
  }
}
