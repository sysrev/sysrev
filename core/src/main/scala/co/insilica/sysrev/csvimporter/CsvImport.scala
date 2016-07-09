package co.insilica.sysrev
package csvimporter

import java.io.{File, FileInputStream, InputStreamReader}

import Implicits._
import co.insilica.sysrev.relationalImporter.Types.{CriteriaId, ArticleId}
import collection.JavaConverters._

import com.opencsv.CSVReader
import doobie.imports._

import Converters._
import scalaz._
import Scalaz._

/**
  * Defines a set of criteria, read from excel document provided.
  * @param include
  * @param notCancer
  * @param notHuman
  * @param notClinical
  * @param notPhase1
  * @param notImmuno
  * @param isConference
  */
case class Criteria(
   include: Boolean,
   notCancer: Boolean,
   notHuman: Boolean,
   notClinical: Boolean,
   notPhase1: Boolean,
   notImmuno: Boolean,
   isConference: Boolean
 ){
  import Criteria._

  /**
    * Convenience method to get map of known criteria names to values.
    */
  def valueMap: Map[String, Boolean] = nameMap.map{
    case (s, f) => (s, f(this))
  }
}

/**
  * Defines a whole row read from the excel sheet
  */
case class ArticleRow(author: String, title: String, journal: String, docAbstract: String, criteria: Criteria)



object Criteria{

  /**
    * From a list of Yes/No, build a [[Criteria]], matching on String "Yes" -> true
    */
  def apply(data: List[String]): Criteria = {
    val facts: List[Boolean] = data.map(_.trim == "Yes")
    Criteria(facts(0), facts(1), facts(2), facts(3), facts(4), facts(5), facts(6))
  }

  /**
    * known criteria by names, and how to get the value for that name out of the row.
    */
  def nameMap : Map[String, Criteria => Boolean] = Map(
    "overall include" -> (_.include),
    "not cancer" -> (_.notCancer),
    "not human" -> (_.notHuman),
    "not clinical trial" -> (_.notClinical),
    "not phase 1" -> (_.notPhase1),
    "not immunotherapy" -> (_.notImmuno),
    "conference abstract" -> (_.isConference)
  )
}

object CsvImport{

  /**
    * Read a csv file into [[ArticleRow]]s
    */
  def getArticlesFromFile(file: File): Stream[ArticleRow] = {
    val reader = new CSVReader(new InputStreamReader(new FileInputStream(file)))
    reader.iterator().asScala.toStream.drop(1).map { row =>
      ArticleRow(
        row(0),
        row(1).trim().dropRight(1), /// hack to get rid of period at end.
        row(2),
        row(3),
        Criteria(row.drop(4).toList))
    }
  }

  /**
    * Get ids for all the criteria in the database, so we can save to them by name.
    * @return a Map of unique criteria names to database ids.
    */
  def criteriaNames: ConnectionIO[Map[String, CriteriaId]] = relationalImporter.Queries.allCriteria.map{ cs =>
    cs.map(c => (c.t.name, c.id)).toMap
  }

  /**
    * Look up article by title, and attach criteria answers to it.
    */
  def linkArticleByTitleWithCriteria(title: String, criteria: Criteria): ConnectionIO[Int] = {

    val jobs : OptionT[ConnectionIO, List[Int]] = for {
      article <- relationalImporter.Queries.articleByTitlePrefix(title) |> (OptionT apply _)
      allCriteria <- criteriaNames                                      |> liftOptionC
      links <- {
        val subjobs: List[ConnectionIO[CriteriaId]] = criteria.valueMap.toList.map {
          case (n, answer) =>
            val cid = allCriteria(n)
            val aid = article.id
            relationalImporter.Queries.articleCriteriaRespond(aid, cid, answer)
        }

        subjobs.sequenceU |> liftOptionC
      }
    } yield links

    jobs.run.map(_.getOrElse(Nil: List[Int]).sum)
  }


}