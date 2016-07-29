package co.insilica
package sysrev
package csvimporter

import java.io.File

import Implicits._

import co.insilica.sysrev.relationalImporter.Types.{WithArticleId, ArticleId}
import org.scalatest._
import scala.concurrent.Future
import scalaz._
import Scalaz._
import doobie.imports._
import dataProvider.TaskFutureOps._


class CsvImportTestSpec extends AsyncFlatSpec with Matchers{
  val tx = Implicits.transactor

  val criteriaCsv: File = new File(config.dataRootPath.get, config.criteriaCsvFileName)

  // "Articles"
  ignore should "be imported from file" in {
    val firstRow = CsvImport.getArticlesFromFile(criteriaCsv).take(1).head
    info("Read first row")
    info(firstRow.toString())
    firstRow.author.length should be > (5)
  }

  // "Criteria answers"
  ignore should "be inserted" in {
    val rs: List[ArticleRow] = CsvImport.getArticlesFromFile(criteriaCsv).toList

    val jobs : List[ConnectionIO[Int]] = rs.map{ r =>
      CsvImport.linkArticleByTitleWithCriteria(r.title.trim().dropRight(1), r.criteria)
    }

    jobs.sequenceU.transact(tx).runFuture.map(_.sum should equal (rs.length * Criteria.nameMap.size))
  }
}
