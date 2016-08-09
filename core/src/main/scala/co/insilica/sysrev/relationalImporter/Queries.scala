package co.insilica.sysrev
package relationalImporter

import doobie.imports._
import doobie.contrib.postgresql.pgtypes._

import scalaz._
import Scalaz._

import Converters._
import relationalImporter.Types._


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

object Queries {

  type ScoredArticle = WithArticleId[WithScore[ArticleWithoutKeywords]]

  object insert {
    /**
      * Insert an article without regard to references.
      */
    def articleBody(a: Article): Update0 =
      sql"""
      insert into article (primary_title, secondary_title, abstract, work_type, remote_database_name, year, authors, urls, document_ids)
      values (${a.primaryTitle}, ${a.secondaryTitle}, ${a.sysRev.docabstract}, ${a.work_type}, ${a.remoteDatabaseName}, ${a.year}, ${a.authors}, ${a.urls}, ${a.documentIds})
    """.update

    def criteria(c: Criteria): Update0 =
      sql"""
      insert into criteria (name, question, is_exclusion, is_inclusion)
      values (${c.name}, ${c.questionText}, ${c.isExclusion}, ${c.isInclusion})
    """.update


    def keywordsQ: Update[String] = Update[String]("insert into keyword (keyword_text) values (?)")

    /**
      * Insert many keywords into database
      */
    def keywords(kwds: List[String]): ConnectionIO[Int] = keywordsQ.updateMany(kwds)

    def addCriteriaAnswer(aid: ArticleId, cid: CriteriaId, value: Boolean): Update0 =
      sql"""
      insert into article_criteria (article_id, criteria_id, answer)
      values ($aid, $cid, $value)
    """.update
  }

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


  def rankedArticlesPage(pageNum: Int = 0): ConnectionIO[List[ScoredArticle]] =
    select.rankedArticlesAllWithAbstracts(pageNum * 200, 200).list

  def rankedArticlesPageFilterCriteria(criteriaIds: List[CriteriaFilter]): ConnectionIO[List[ScoredArticle]] =
    criteriaIds.toNel.map(select.rankedArticlesPageFilterCriteria(_).list).getOrElse(List[ScoredArticle]().point[ConnectionIO])


  def insertArticleBody(a: Article): ConnectionIO[CriteriaId] = insert.articleBody(a).withUniqueGeneratedKeys("criteria_id")

  def articleBodyByTitlePrefix(tp: String): ConnectionIO[List[WithArticleId[ArticleWithoutKeywords]]] =
    select.articleBodyByTitlePrefix(tp).list

  def keywordsForArticle(aid: ArticleId): ConnectionIO[List[WithKeywordId[String]]] = select.keywordsForArticle(aid).list

  def articleByTitlePrefix(tp: String): ConnectionIO[Option[WithArticleId[Article]]] = {
    val articleBodyQ: ConnectionIO[Option[WithArticleId[ArticleWithoutKeywords]]] = articleBodyByTitlePrefix(tp).map(_.headOption)

    val r: OptionT[ConnectionIO, WithArticleId[Article]] = for {
      a <- articleBodyQ |> (OptionT apply _)
      val ad = a.t
      val aid = a.id
      kws <- keywordsForArticle(aid) |> liftOptionC
      kwNames = kws.map(_.t)
    } yield WithAnyId(
      aid,
      Article(
        SysRev(Titles(ad.title, ad.title2), ad.abs, kwNames),
        ad.authors.getOrElse(Nil),
        ad.work_type,
        ad.rdn,
        ad.year,
        None,
        ad.urls.getOrElse(Nil),
        ad.documentIds.getOrElse(Nil)))

    r.run
  }


  def getKeywordIds(words: List[String]): ConnectionIO[List[KeywordId]] =
    words.toNel.map(select.keywords(_).list).getOrElse(List[KeywordId]().point[ConnectionIO])

  /**
    * Connect a single article id to many keyword ids.
    */
  def connectMany(aid: ArticleId, kwids: List[KeywordId]): ConnectionIO[Int] = {
    val sql = "insert into article_keyword (keyword_id, article_id) values (?, ?)"
    Update[(KeywordId, ArticleId)](sql).updateMany(kwids.map((_, aid)))
  }

  /**
    * Insert an article, making sure to connect to any keywords that exist.
    * Assumes all keywords are already inserted into db.
    */
  def insertArticle(a: Article): ConnectionIO[Int] = for {
    aid <- insertArticleBody(a)
    kwids <- getKeywordIds(a.sysRev.keywords)
    links <- connectMany(aid, kwids)
  } yield links

  def insertCriteria(c: Criteria): ConnectionIO[CriteriaId] = insert.criteria(c).withUniqueGeneratedKeys("criteria_id")

  def allCriteria(): ConnectionIO[List[WithCriteriaId[Criteria]]] = select.allCriteria().list

  def articleCriteriaRespond(aid: ArticleId, cid: CriteriaId, answer: Boolean): ConnectionIO[Int] =
    insert.addCriteriaAnswer(aid, cid, answer).run

  def allCriteriaResponses: ConnectionIO[Map[ArticleId, List[CriteriaResponse]]] = {
    val qr: ConnectionIO[List[WithArticleId[CriteriaResponse]]] = select.allCriteriaResponses.list
    qr.map(_.foldLeft(Map[ArticleId, List[CriteriaResponse]]()){
      case (acc, WithArticleId(aid, resp)) if acc.contains(aid) => acc + (aid -> (resp :: acc(aid)))
      case (acc, WithArticleId(aid, resp)) => acc + (aid -> List(resp))
      case (acc, _) => acc
    })
  }

  def articlesById(ids: List[ArticleId]) : ConnectionIO[List[ScoredArticle]] =
    ids.toNel.map {
      select.articlesById(_).list
    } getOrElse {
      List[ScoredArticle]().point[ConnectionIO]
    }

  def textSearch(text: String) : ConnectionIO[List[ScoredArticle]] = select.textSearch(text).list
}

