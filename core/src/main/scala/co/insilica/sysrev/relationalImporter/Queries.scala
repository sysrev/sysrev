package co.insilica.sysrev.relationalImporter

import co.insilica.sysrev.Article

import doobie.imports._
import doobie.contrib.postgresql.pgtypes._

import scalaz._
import Scalaz._

import Types._

object Queries{

  def insertArticleBody(a: Article): ConnectionIO[ArticleId] = sql"""
    insert into article (primary_title, secondary_title, abstract, work_type, remote_database_name, year, authors, urls)
    values (${a.primaryTitle}, ${a.secondaryTitle}, ${a.sysRev.docabstract}, ${a.work_type}, ${a.remoteDatabaseName}, ${a.year}, ${a.authors}, ${a.urls})
  """.update.withUniqueGeneratedKeys("article_id")

  def insertKeywords(kwds: List[String]) : ConnectionIO[Int] = {
    val sql = "insert into keyword (keyword_text) values (?)"
    Update[String](sql).updateMany(kwds)
  }


  def getKeywords(words: List[String]): ConnectionIO[List[KeywordId]] = words.toNel.map { neKwds =>
    implicit val kwdsParam = Param.many(neKwds)

    sql"""
      select keyword_id
      from keyword
      where keyword_text in (${neKwds: neKwds.type})
    """.query[KeywordId].list
  } getOrElse {
    List[KeywordId]().point[ConnectionIO]
  }

  def connectMany(aid: ArticleId, kwids: List[KeywordId]): ConnectionIO[Int] = {
    val sql = "insert into article_keyword (keyword_id, article_id) values (?, ?)"
    Update[(KeywordId, ArticleId)](sql).updateMany(kwids.map((_, aid)))
  }

  def insertArticle(a: Article): ConnectionIO[Int] = for {
    aid <- insertArticleBody(a)
    kwids <- getKeywords(a.keywords)
    links <- connectMany(aid, kwids)
  } yield links
}