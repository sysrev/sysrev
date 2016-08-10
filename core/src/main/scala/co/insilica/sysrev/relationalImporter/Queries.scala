package co.insilica.sysrev
package relationalImporter

import doobie.imports._

import scalaz._
import Scalaz._

import Converters._
import relationalImporter.queries._
import relationalImporter.queries.Types._


object Queries {
  type ScoredArticle = WithArticleId[WithScore[ArticleWithoutKeywords]]

  def rankedArticlesPage(pageNum: Long = 0): ConnectionIO[List[ScoredArticle]] =
    select.rankedArticlesAllWithAbstracts(pageNum * 200L, 200L).list

  def rankedArticlesPageFilterCriteria(criteriaIds: List[CriteriaFilter]): ConnectionIO[List[ScoredArticle]] =
    criteriaIds.toNel.map(select.rankedArticlesPageFilterCriteria(_).list).getOrElse(List[ScoredArticle]().point[ConnectionIO])

  def insertArticleBody(a: Article): ConnectionIO[CriteriaId] = insert.articleBody(a).withUniqueGeneratedKeys("article_id")

  def articleBodyByTitlePrefix(tp: String): ConnectionIO[List[WithArticleId[ArticleWithoutKeywords]]] =
    select.articleBodyByTitlePrefix(tp).list

  def keywordsForArticle(aid: ArticleId): ConnectionIO[List[WithKeywordId[String]]] = select.keywordsForArticle(aid).list

  def articleByTitlePrefix(tp: String): ConnectionIO[Option[WithArticleId[Article]]] = {
    val articleBodyQ: ConnectionIO[Option[WithArticleId[ArticleWithoutKeywords]]] = articleBodyByTitlePrefix(tp).map(_.headOption)

    val r: OptionT[ConnectionIO, WithArticleId[Article]] = for {
      a <- articleBodyQ |> (OptionT apply _)
      ad = a.t
      aid = a.id
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

  def augmentArticleWithDocumentIdsAndAuthors(articleId: ArticleId, documentIds: List[String], authors: List[String]) : ConnectionIO[Int] =
    update.updateArticleWithDocumentIdsAndAuthors(articleId, documentIds, authors).run


  def articlesById(ids: List[ArticleId]) : ConnectionIO[List[ScoredArticle]] =
    ids.toNel.map {
      select.articlesById(_).list
    } getOrElse {
      List[ScoredArticle]().point[ConnectionIO]
    }

  def textSearch(text: String) : ConnectionIO[List[ScoredArticle]] = select.textSearch(text).list
}

