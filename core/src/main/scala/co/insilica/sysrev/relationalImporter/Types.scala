package co.insilica.sysrev
package relationalImporter

object Types{
  case class WithAnyId[I, T](id: I, t: T)

  type KeywordId = Int
  type WithKeywordId[T] = WithAnyId[KeywordId, T]

  type ArticleId = Int
  type WithArticleId[T] = WithAnyId[ArticleId, T]

  type CriteriaId = Int
  type WithCriteriaId[T] = WithAnyId[CriteriaId, T]

  case class Criteria(
     name: String,
     questionText: String,
     isInclusion: Boolean,
     isExclusion: Boolean
  )
  object Criteria{
    def knownCriteria: List[Criteria] = List(
      Criteria("overall include", "Include?", true, false),
      Criteria("not cancer", "Not related to cancer? (Yes if not related)", false, true),
      Criteria("not human", "Not a human study? (Yes if not a human study)", false, true),
      Criteria("not clinical trial", "Not a clinical trial? (Yes if not a clinical trial", false, true),
      Criteria("not phase 1", "Not a phase 1 trial? (Yes if not a phase 1 trial", false, true),
      Criteria("not immunotherapy", "Not immunotherapy? (Yes if not immunotherapy)", false, true),
      Criteria("conference abstract", "Conference abstract? (Yes if conference abstract", false, true)


    )
  }

}