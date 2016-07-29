package co.insilica.sysrev.data

import co.insilica.sysrev.data.Types.ReviewTag
import doobie.util.transactor.Transactor
import org.scalatest._
import doobie.contrib.scalatest.analysisspec.AnalysisSpec

import scalaz.concurrent.Task


class QueriesTestSpec extends FlatSpec with AnalysisSpec {
  override def transactor: Transactor[Task] = co.insilica.sysrev.Implicits.transactor

  val tag = ReviewTag(1, 2, Some(true))

  "Api queries" should "typecheck" in {
    check(Queries.tagArticleQ(1, tag))
    check(Queries.updateTagArticleQ(1, tag))
  }
}
