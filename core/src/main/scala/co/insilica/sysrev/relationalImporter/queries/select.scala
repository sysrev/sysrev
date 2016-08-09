package co.insilica.sysrev
package relationalImporter.queries

import co.insilica.sysrev.relationalImporter.queries._
import co.insilica.sysrev.relationalImporter.queries.Types._

import doobie.imports._
import doobie.contrib.postgresql.pgtypes._


import scalaz.NonEmptyList

object select {
  def articleBodyByTitlePrefix(tp: String): Query0[WithArticleId[ArticleWithoutKeywords]] = {
    val q = s"$tp%"
    sql"""
       select article_id, primary_title, secondary_title, abstract, authors, work_type, remote_database_name, year, urls, document_ids
       from article
       where primary_title ilike $q
     """.query[WithArticleId[ArticleWithoutKeywords]]
  }

  def keywordsForArticle(aid: ArticleId) = sql"""
      select keyword_id, keyword_text
      from article_keyword
      left join keyword using (keyword_id)
      where article_id = $aid
    """.query[WithKeywordId[String]]

  /**
    * Get a list of keywords out of the database from a list of strings.
    */
  def keywords(words: NonEmptyList[String]): Query0[KeywordId] = {
    implicit val kwdsParam = Param.many(words)

    sql"""
        select keyword_id
        from keyword
        where keyword_text in (${words: words.type})
      """.query[KeywordId]
  }

  def allCriteria(): Query0[WithCriteriaId[Criteria]] = sql"""
      select criteria_id, name, question, is_exclusion, is_inclusion
      from criteria
      """.query[WithCriteriaId[Criteria]]

  def criteriaIdByName(name: String): Query0[CriteriaId] = sql"""
      select criteria_id
      from criteria
      where name = $name
      """.query[CriteriaId]


  def articlesWithCriteriaAnswer(cid: CriteriaId): Query0[(WithArticleId[ArticleWithoutKeywords], Option[Boolean])] = sql"""
      select article_id, primary_title, secondary_title, abstract, authors, work_type, remote_database_name, year, urls, document_ids, answer
      from article
      left join article_criteria using (article_id)
      where criteria_id = $cid
    """.query[(WithArticleId[ArticleWithoutKeywords], Option[Boolean])]

  def rankedArticlesAll(start: Long = 0, numrows: Long = 200): Query0[ScoredArticle] =
    sql"""
      select article_id, primary_title, secondary_title, abstract, authors, work_type, remote_database_name, year, urls, document_ids, _2 as score
      from article
      left join article_ranking on _1 = article_id
      order by score asc
      limit $numrows
      offset $start
    """.query


  def rankedArticlesAllWithAbstracts(start: Long = 0, numrows: Long = 200): Query0[ScoredArticle] =
    sql"""
      select article_id, primary_title, secondary_title, abstract, authors, work_type, remote_database_name, year, urls, document_ids, _2 as score
      from article
      left join article_ranking on _1 = article_id
      where (abstract = '') is not true
      order by score asc
      limit $numrows
      offset $start
    """.query


  def rankedArticlesPageFilterCriteria(criteriaIds: NonEmptyList[CriteriaFilter]): Query0[ScoredArticle] = {
    implicit val hasCriteria = criteriaIds.map(_.id)
    implicit val param = Param.many(hasCriteria)

    sql"""
        select article_id, primary_title, secondary_title, abstract, authors, work_type, remote_database_name, year, urls, documentIds, _2 as score
        from article
        left join article_ranking on _1 = article_id
        left join article_criteria using (article_id)
        left join criteria using (criteria_id)
        where criteria_id in ${hasCriteria: hasCriteria.type}
      """.query
  }

  def criteriaResponsesFor(articleIds: NonEmptyList[ArticleId]): Query0[WithArticleId[WithCriteriaId[Option[Boolean]]]] = {
    implicit val param = Param.many(articleIds)
    sql"""
          select article_id, criteria_id, answer
          from article_criteria
          where article_id in (${articleIds: articleIds.type})
        """.query
  }

  def allCriteriaResponses: Query0[WithArticleId[CriteriaResponse]] =
    sql"""
        select article_id, criteria_id, answer
        from article_criteria
      """.query

  def articlesById(articleIds: NonEmptyList[ArticleId]): Query0[ScoredArticle] = {
    implicit val param = Param.many(articleIds)

    sql"""
        select article_id, primary_title, secondary_title, abstract, authors, work_type, remote_database_name, year, urls, document_ids, _2 as score
        from article
        left join article_ranking on _1 = article_id
        where article_id in (${articleIds: articleIds.type})
      """.query
  }

  def textSearch(text: String): Query0[ScoredArticle] = {
    val searchTerms = s"%$text%"
    sql"""
        select article_id, primary_title, secondary_title, abstract, authors, work_type, remote_database_name, year, urls, document_ids, _2 as score
        from article
        left join article_ranking on _1 = article_id
        where primary_title ilike $searchTerms or abstract ilike $searchTerms
        order by score asc
        limit 100
      """.query
  }
}