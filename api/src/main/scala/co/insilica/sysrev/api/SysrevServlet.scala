package co.insilica.sysrev
package api

import co.insilica.apistack.{ApiStack, ResultWrapSupport}
import co.insilica.sysrev.indexing.{QueryEnv, DocIndex}
import co.insilica.sysrev.relationalImporter.Types.{WithArticleId, ArticleId}
import co.insilica.sysrev.relationalImporter._

import org.scalatra._
import doobie.imports._

import scalaz._
import Scalaz._
import scalaz.concurrent.Task

case class ArticleIds(articleIds: List[ArticleId])
case class ErrorResult(msg: String)

class SysrevServlet extends ApiStack with FutureSupport with ResultWrapSupport {

  implicit val tx: Transactor[Task] = Implicits.transactor

  def getRankedPage(p: Int = 0) : Task[List[WithArticleId[WithScore[ArticleWithoutKeywords]]]] =
    Queries.rankedArticlesPage(p).transact(tx)

  getT("/ranking/:page") {
    params("page").parseInt.toOption.map(getRankedPage).getOrElse(getRankedPage(0))
  }

  getT("/ranking") {
    getRankedPage(0)
  }

  getT("/criteria") {
    Queries.allCriteria().transact(tx)
  }

  /**
    * Expects {:articleIds [list of numbers]}
    */
  postT("/article_criteria"){
    parsedBody.extractOpt[ArticleIds].map {
      case ArticleIds(articleIds) => Queries.criteriaResponsesFor(articleIds).transact(tx)
    } getOrElse {
      Task.now(ErrorResult("Article ids must not be empty, must be numbers."))
    }
  }

  /**
    * Returns a map articleid -> List[criteriaid, boolean]
    */
  getT("/allcriteria"){
    Queries.allCriteriaResponses.transact(tx)
  }
}
