package co.insilica.sysrev
package relationalImporter

import doobie.imports._
import doobie.contrib.postgresql.pgtypes._

import scalaz._
import Scalaz._

import Converters._
import relationalImporter.Types._

case class ArticleWithoutKeywords(
  title: String,
  title2: Option[String],
  abs: Option[String],
  authors: Option[List[String]],
  work_type: Option[String],
  rdn: Option[String],
  year: Option[Int],
  urls: Option[List[String]]
)

case class WithScore[T](item: T, score: Double)

object Queries{

  object insert {
    /**
      * Insert an article without regard to references.
      */
    def articleBody(a: Article): Update0 = sql"""
      insert into article (primary_title, secondary_title, abstract, work_type, remote_database_name, year, authors, urls)
      values (${a.primaryTitle}, ${a.secondaryTitle}, ${a.sysRev.docabstract}, ${a.work_type}, ${a.remoteDatabaseName}, ${a.year}, ${a.authors}, ${a.urls})
    """.update

    def criteria(c: Criteria): Update0 = sql"""
      insert into criteria (name, question, is_exclusion, is_inclusion)
      values (${c.name}, ${c.questionText}, ${c.isExclusion}, ${c.isInclusion})
    """.update

    /**
      * Insert many keywords into database
      */
    def keywords(kwds: List[String]) : ConnectionIO[Int] = {
      val sql = "insert into keyword (keyword_text) values (?)"
      Update[String](sql).updateMany(kwds)
    }

    def addCriteriaAnswer(aid: ArticleId, cid: CriteriaId, value: Boolean): Update0 = sql"""
      insert into article_criteria (article_id, criteria_id, answer)
      values ($aid, $cid, $value)
    """.update
  }

  object select {
    def articleBodyByTitlePrefix(tp: String): Query0[WithArticleId[ArticleWithoutKeywords]] = {
      val q = s"$tp%"
      sql"""
       select article_id, primary_title, secondary_title, abstract, authors, work_type, remote_database_name, year, urls
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
      select article_id, primary_title, secondary_title, abstract, authors, work_type, remote_database_name, year, urls, answer
      from article
      left join article_criteria using (article_id)
      where criteria_id = $cid
    """.query[(WithArticleId[ArticleWithoutKeywords], Option[Boolean])]

    def rankedArticlesAll(start: Int = 0, numrows: Int = 200): Query0[WithArticleId[WithScore[ArticleWithoutKeywords]]] = sql"""
      select article_id, primary_title, secondary_title, abstract, authors, work_type, remote_database_name, year, urls, _2 as score
      from article
      left join article_ranking on _1 = article_id
      order by score asc
      limit $numrows
      offset $start
    """.query


    def rankedArticlesAllWithAbstracts(start: Int = 0, numrows: Int = 200): Query0[WithArticleId[WithScore[ArticleWithoutKeywords]]] = sql"""
      select article_id, primary_title, secondary_title, abstract, authors, work_type, remote_database_name, year, urls, _2 as score
      from article
      left join article_ranking on _1 = article_id
      where (abstract = '') is not true
      order by score asc
      limit $numrows
      offset $start
    """.query
  }

  def rankedArticlesPage(pageNum: Int = 0): ConnectionIO[List[WithArticleId[WithScore[ArticleWithoutKeywords]]]] =
    select.rankedArticlesAllWithAbstracts(pageNum * 200, 200).list

  def insertArticleBody(a: Article) : ConnectionIO[CriteriaId] = insert.articleBody(a).withUniqueGeneratedKeys("criteria_id")
  def articleBodyByTitlePrefix(tp: String): ConnectionIO[List[WithArticleId[ArticleWithoutKeywords]]] =
    select.articleBodyByTitlePrefix(tp).list
  def keywordsForArticle(aid: ArticleId): ConnectionIO[List[WithKeywordId[String]]] = select.keywordsForArticle(aid).list

  def articleByTitlePrefix(tp: String): ConnectionIO[Option[WithArticleId[Article]]] = {
    val articleBodyQ : ConnectionIO[Option[WithArticleId[ArticleWithoutKeywords]]] = articleBodyByTitlePrefix(tp).map(_.headOption)

    val r : OptionT[ConnectionIO, WithArticleId[Article]] = for {
      a <- articleBodyQ |> (OptionT apply _)
      val ad =  a.t
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
          ad.urls.getOrElse(Nil)))

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

  def articleCriteriaRespond(aid: ArticleId, cid: CriteriaId, answer: Boolean) : ConnectionIO[Int] =
    insert.addCriteriaAnswer(aid, cid, answer).run


}

