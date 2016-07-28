package co.insilica.sysrev
package api

import co.insilica.auth.{ErrorResult, AuthStack, AuthServlet}
import co.insilica.apistack.{Result, ApiStack, ResultWrapSupport}
import co.insilica.sysrev.Implicits
import co.insilica.sysrev.indexing.{QueryEnv, DocIndex}
import co.insilica.sysrev.relationalImporter.Types.{CriteriaId, WithArticleId, ArticleId, WithCriteriaId}
import co.insilica.sysrev.relationalImporter._

import org.scalatra._
import doobie.imports._

import scalaz._
import Scalaz._
import scalaz.concurrent.Task

case class ArticleIds(articleIds: List[ArticleId])

class SysrevAuthServlet extends AuthServlet{
  override protected implicit lazy val transactor: Transactor[Task] = Implicits.transactor
}

class SysrevServlet extends AuthStack with FutureSupport with ResultWrapSupport {
  val tx = Implicits.transactor
  override protected implicit lazy val transactor: Transactor[Task] = tx

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

  get("/user"){
    if(isAuthenticated) Result(user.t.t)
    else ErrorResult("Not authenticated")
  }

}
