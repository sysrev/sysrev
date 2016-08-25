package co.insilica.sysrev
package relationalImporter
package queries
import queries.Types._

import doobie.imports._
import doobie.contrib.postgresql.pgtypes._

import scalaz._
import Scalaz._

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
      insert into criteria (name, question, is_inclusion)
      values (${c.name}, ${c.questionText}, ${c.isInclusion})
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
