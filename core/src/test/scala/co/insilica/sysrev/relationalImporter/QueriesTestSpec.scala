package co.insilica.sysrev
package relationalImporter

import co.insilica.sysrev.relationalImporter.Types.ArticleId
import doobie.contrib.scalatest.analysisspec.AnalysisSpec
import org.scalatest._

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scalaz.NonEmptyList
import doobie.imports._

import co.insilica.dataProvider.TaskFutureOps._

class QueriesTestSpec extends FlatSpec with Matchers with AnalysisSpec {
  implicit val transactor = TestConfig.transactor
  import scala.concurrent.ExecutionContext.Implicits.global

  "Article body queries" should "typecheck" in
    check(Queries.select.articleBodyByTitlePrefix("hello"))

  "Keyword for article query" should "typecheck" in
    check(Queries.select.keywordsForArticle(2))

  "All keywords query" should "typecheck" in
    check(Queries.select.keywords(NonEmptyList("example")))

  "Criteria by name query" should "typecheck" in
    check(Queries.select.criteriaIdByName("hello"))

  "All criteria query" should "typecheck" in
    check(Queries.select.allCriteria())

  "All criteria responses" should "typecheck" in
    check(Queries.select.allCriteriaResponses)

  "Criteria responses for" should "typecheck" in
    check(Queries.select.criteriaResponsesFor(NonEmptyList(12)))

  "Articles by article id" should "typecheck" in
    check(Queries.select.articlesById(NonEmptyList(1,2,3)))

  "Criteria article join query" should "typecheck" in
    check(Queries.select.articlesWithCriteriaAnswer(1))

  "All ranked articles query" should "typecheck" in
    check(Queries.select.rankedArticlesAll(0,1))

  "Ranked articles with abstracts query" should "typecheck" in
    check(Queries.select.rankedArticlesAllWithAbstracts(0,1))

  "The ranked articles query" should "get something" in {
    val r = Queries.rankedArticlesPage(0).transact(transactor)

    Await.result(r.runFuture.map(_.length should be > (100)), Duration.Inf)
  }
}