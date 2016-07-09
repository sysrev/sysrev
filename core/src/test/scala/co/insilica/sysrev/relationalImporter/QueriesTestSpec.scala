package co.insilica.sysrev
package relationalImporter

import doobie.contrib.scalatest.analysisspec.AnalysisSpec
import org.scalatest._

import scalaz.NonEmptyList

class QueriesTestSpec extends FlatSpec with AnalysisSpec {
  implicit val transactor = Implicits.transactor

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

  "Criteria article join query" should "typecheck" in
    check(Queries.select.articlesWithCriteriaAnswer(1))
}