package co.insilica.sysrev
package data

import doobie.util.transactor.Transactor
import org.scalatest._
import doobie.contrib.scalatest.analysisspec.AnalysisSpec

import scalaz.concurrent.Task


class QueriesTestSpec extends FlatSpec with AnalysisSpec {
  override def transactor: Transactor[Task] = TestConfig.transactor

  val tag = ReviewTag(1, 2, Some(true))

  "Api queries" should "typecheck tagarticle" in check(Queries.tagArticleQ(1, tag))

  it should "typecheck updatetagarticle properly" in check(Queries.updateTagArticleQ(1, tag))

  it should "typecheck user summary data query" in check(Queries.usersSummaryDataQ)

  it should "typecheck labeling task query" in check(Queries.getLabelingTaskByHighestRankQ(10))

  it should "typecheck article responses query" in check(Queries.responsesForArticleQ(2))
}
