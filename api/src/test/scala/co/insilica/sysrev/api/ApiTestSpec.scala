package co.insilica.sysrev
package api


import co.insilica.apistack.test.{PostSupport, SuiteJsonSupport}
import co.insilica.sysrev.relationalImporter.CriteriaResponse
import co.insilica.sysrev.relationalImporter.Types.{ArticleId, CriteriaId}
import org.scalatest._
import org.scalatra.test.scalatest.ScalatraSuite
import language.implicitConversions
import org.json4s._

class ApiTestSpec extends ScalatraSuite with FlatSpecLike with Matchers with SuiteJsonSupport with PostSupport {
  override implicit val jsonFormats = DefaultFormats
  implicit lazy val transactor = Implicits.transactor

  def okStatus = status should equal (200)

  addServlet(classOf[SysrevServlet], "/*")

  "ranking url status" should "be 200" in {
    get("/ranking") {
      status should equal (200)
    }
  }

  "criteria" should "be received" in {
    session{
      get("/allcriteria"){
        parse(response.body).extractOpt[Map[ArticleId, List[CriteriaResponse]]].map{ resMap =>
          resMap.size should be > (10)
        }
      }
    }
  }
}
