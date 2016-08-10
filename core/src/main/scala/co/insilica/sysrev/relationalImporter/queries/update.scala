package co.insilica.sysrev

package relationalImporter.queries

import Types._
import doobie.imports._

// Needed for postgres arrays.
import doobie.contrib.postgresql.pgtypes._


object update{
  def updateArticleWithDocumentIdsAndAuthors(aid: ArticleId, documentIds: List[String], authors: List[String]): Update0 = sql"""
    update article
    set document_ids = $documentIds, authors = $authors
    where article_id = $aid
  """.update
}