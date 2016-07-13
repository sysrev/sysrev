package co.insilica.sysrev
package api

import co.insilica.apistack.{ApiStack, ResultWrapSupport}
import co.insilica.sysrev.indexing.{QueryEnv, DocIndex}
import DocIndex._
import Types._
import QueryEnv._
import co.insilica.sysrev.relationalImporter.Queries.ArticleWithoutKeywords
import co.insilica.sysrev.relationalImporter.Types.WithArticleId

import org.scalatra._
import doobie.imports._

import play.api.libs.iteratee.Iteratee

import scalaz._
import Scalaz._
import scalaz.concurrent.Task

class SysrevServlet extends ApiStack with FutureSupport with ResultWrapSupport {

  implicit val tx: Transactor[Task] = Implicits.transactor

  get("/") {
    "up"
  }

  def getRankedPage(p: Int) : Task[List[WithArticleId[(ArticleWithoutKeywords, Double)]]] =
    relationalImporter.Queries.rankedArticlesPage(0).transact(tx)

  get("/ranking/:page") {
    params("page").parseInt.toOption.map(getRankedPage).getOrElse(getRankedPage(0))
  }

  getT("/ranking") {
    getRankedPage(0)
  }
}

