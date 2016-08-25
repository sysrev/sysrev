package co.insilica.sysrev
package relationalImporter

import co.insilica.sysrev.relationalImporter.queries.Types.ArticleId
import doobie.contrib.scalatest.analysisspec.AnalysisSpec
import org.scalatest._

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scalaz.NonEmptyList
import doobie.imports._

import queries._

import co.insilica.dataProvider.TaskFutureOps._

class QueriesTestSpec extends FlatSpec with Matchers with AnalysisSpec {
  implicit val transactor = TestConfig.transactor

  val article = Article(SysRev(Titles("First", Option("second")), Option("hi"), Nil), Nil, None, None, None, None, Nil, Nil)

  "Article body insert" should "typecheck" in
    check(insert.articleBody(article))

  "Criteria insert" should "typecheck" in
    check(insert.criteria(Criteria.knownCriteria.head))

  "Criteria answer insert" should "typecheck" in
    check(insert.addCriteriaAnswer(2, 1, true))

  "Keyword insert" should "typecheck" in
    check(insert.keywordsQ)

  "DocumentIds update" should "typecheck" in
    check(update.updateArticleWithDocumentIdsAndAuthors(1, List("hi"), List("hi2")))

  "Article body queries" should "typecheck" in
    check(select.articleBodyByTitlePrefix("hello"))

  "Keyword for article query" should "typecheck" in
    check(select.keywordsForArticle(2))

  "All keywords query" should "typecheck" in
    check(select.keywords(NonEmptyList("example")))

  "Criteria by name query" should "typecheck" in
    check(select.criteriaIdByName("hello"))

  "All criteria query" should "typecheck" in
    check(select.allCriteria())

  "All criteria responses" should "typecheck" in
    check(select.allCriteriaResponses)

  "Criteria responses for" should "typecheck" in
    check(select.criteriaResponsesFor(NonEmptyList(12)))

  "Articles by article id" should "typecheck" in
    check(select.articlesById(NonEmptyList(1,2,3)))

  "Criteria article join query" should "typecheck" in
    check(select.articlesWithCriteriaAnswer(1))

  "All ranked articles query" should "typecheck" in
    check(select.rankedArticlesAll(0,1))

  "Ranked articles with abstracts query" should "typecheck" in
    check(select.rankedArticlesAllWithAbstracts(0,1))
}