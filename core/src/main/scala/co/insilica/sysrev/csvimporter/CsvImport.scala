package co.insilica.sysrev
package csvimporter

import java.io.{File, FileInputStream, InputStreamReader}

import Implicits._
import collection.JavaConverters._

import com.opencsv.CSVReader


case class Criteria(include: Boolean, notCancer: Boolean, notHuman: Boolean, notClinical: Boolean, notPhase1: Boolean, notImmuno: Boolean, isConference: Boolean)

object Criteria{
  def apply(data: List[String]): Criteria = {
    val facts : List[Boolean] = data.map(_.trim == "Yes")
    Criteria(facts(0), facts(1), facts(2), facts(3), facts(4), facts(5), facts(6))
  }
}

case class ArticleRow(author: String, title: String, journal: String, docAbstract: String, criteria: Criteria)

object CsvImport{

  def getArticlesFromFile: Stream[ArticleRow] = {
    val reader = new CSVReader(new InputStreamReader(new FileInputStream(new File(config.dataRootPath.get, config.criteriaCsvFileName))))
    reader.iterator().asScala.toStream.drop(1).map { row =>
      ArticleRow(
        row(0),
        row(1).trim().dropRight(1), /// hack to get rid of period at end.
        row(2),
        row(3),
        Criteria(row.drop(4).toList))
    }
  }


}