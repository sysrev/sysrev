package co.insilica.sysrev
package api

import co.insilica.apistack.{ApiStack, ResultWrapSupport}
import co.insilica.sysrev.indexing.{QueryEnv, DocIndex}
import co.insilica.sysrev.relationalImporter._
import co.insilica.sysrev.relationalImporter.Types._

import org.scalatra._
import doobie.imports._

import scalaz._
import Scalaz._
import scalaz.concurrent.Task

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
}
