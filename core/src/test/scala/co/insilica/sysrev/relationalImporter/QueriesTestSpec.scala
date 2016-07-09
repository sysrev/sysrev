package co.insilica.sysrev
package relationalImporter

import doobie.contrib.scalatest.analysisspec.AnalysisSpec
import org.scalatest._

import scalaz.NonEmptyList

class QueriesTestSpec extends FlatSpec with AnalysisSpec {
  implicit val transactor = Implicits.transactor
  check(Queries.select.articleBodyByTitlePrefix("hello"))
  check(Queries.select.keywordsForArticle(2))
  check(Queries.select.keywords(NonEmptyList("example")))
}