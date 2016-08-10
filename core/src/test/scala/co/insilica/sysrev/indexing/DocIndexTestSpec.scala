package co.insilica
package sysrev
package indexing

import org.scalatest._
import play.api.libs.iteratee.{Enumerator, Iteratee}
import DocIndex._
import QueryEnv._
import co.insilica.sysrev.Types._
import reactivemongo.bson.BSONDocument

import scala.concurrent.Future

class DocIndexTestSpec extends AsyncFlatSpec with Matchers {
  import TestConfig._

  "The doc importer" should "read out documents" in {
    sysrevImporter().select[SysRev]() flatMap (_ |>>> Iteratee.takeUpTo(5)) map { docs =>
      docs.length should equal (5)
    }
  }

  val testTitle = "Use of an attenuated version of a strongly immunogenic, peptidebased vaccine to enhance an anti-cancer immune response against folate receptor-? (FR?)"
  case class PrimaryTitle(title : String)
  implicit val titleQueryReader = makeWriter{ t: PrimaryTitle => BSONDocument("titles.title" -> t.title)}
  val titleQuery = PrimaryTitle(testTitle)

  def testArticle: Future[Option[Article]] = {
    val enum : Future[Enumerator[Article]] = sysrevImporter().select(titleQuery)
    enum.flatMap(_ |>>> Iteratee.head)
  }



  "Article" should "contain document_ids" in testArticle.map (_ map (_.documentIds should not be empty) should not be None)

  "Article" should "contain authors" in testArticle.map(_ map (_.authors should not be empty) should not be None)
}
