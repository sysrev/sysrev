package co.insilica.sysrev.relationalImporter.queries

import scalaz._
import Scalaz._
import Types._

case class WithAnyId[I, T](id: I, t: T)

// Having the List of Strings in there, mapped to the postgres array, requires the import
// of doobie.contrib.postgresql.pgtypes._ if using this in a query.
case class ArticleWithoutKeywords(
                                   title: String,
                                   title2: Option[String],
                                   abs: Option[String],
                                   authors: Option[List[String]],
                                   work_type: Option[String],
                                   rdn: Option[String],
                                   year: Option[Int],
                                   urls: Option[List[String]],
                                   documentIds: Option[List[String]]
                                 )

case class WithScore[T](item: T, score: Double)

case class CriteriaFilter(id: CriteriaId, answer: Option[Boolean])

case class CriteriaResponse(id: CriteriaId, answer: Option[Boolean])

object Types {
  type KeywordId = Int
  type WithKeywordId[T] = WithAnyId[KeywordId, T]
  val WithKeywordId = WithAnyId

  type ArticleId = Int
  type WithArticleId[T] = WithAnyId[ArticleId, T]
  val WithArticleId = WithAnyId

  type CriteriaId = Int
  type WithCriteriaId[T] = WithAnyId[CriteriaId, T]
  val WithCriteriaId = WithAnyId

  type ScoredArticle = WithArticleId[WithScore[ArticleWithoutKeywords]]
}

case class Criteria(
  name: String,
  questionText: String,
  isInclusion: Option[Boolean]
)

object Criteria{
  def knownCriteria: List[Criteria] = List(
    Criteria("overall include", "Include?",  true.some),
    Criteria("not cancer", "Not related to cancer? (Yes if not related)", false.some),
    Criteria("not human", "Not a human study? (Yes if not a human study)", false.some),
    Criteria("not clinical trial", "Not a clinical trial? (Yes if not a clinical trial", false.some),
    Criteria("not phase 1", "Not a phase 1 trial? (Yes if not a phase 1 trial", false.some),
    Criteria("not immunotherapy", "Not immunotherapy? (Yes if not immunotherapy)", false.some),
    Criteria("conference abstract", "Conference abstract? (Yes if conference abstract", false.some)
  )
}