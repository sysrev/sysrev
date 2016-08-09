package co.insilica.sysrev
package api

import co.insilica.apistack.test.{PostSupport, SuiteJsonSupport}
import co.insilica.auth.Login
import co.insilica.sysrev.data.UserArticles
import co.insilica.sysrev.relationalImporter.queries.CriteriaResponse
import co.insilica.sysrev.relationalImporter.queries.Types.{ArticleId, CriteriaId}
import org.scalatest._
import org.scalatra.test.scalatest.ScalatraSuite
import language.implicitConversions
import org.json4s._

case class UserStatusResult(result: List[UserArticles])

class ApiTestSpec extends ScalatraSuite with FlatSpecLike with Matchers with SuiteJsonSupport with PostSupport {
  override implicit val jsonFormats = DefaultFormats
  implicit lazy val transactor = TestConfig.transactor

  def okStatus = status should equal (200)

  addServlet(classOf[SysrevServlet], "/*")

  def login() = postAsJson("/login", Login("hui.li@example.com", "huihuihui"))(okStatus)

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

  "user status summary" should "be retrieved" in {
    session{
      login()
      get("/users"){
        parse(response.body).extractOpt[UserStatusResult].map {
          case UserStatusResult(articles) => articles should not be empty
        } should not be (None)
      }
    }
  }
}
