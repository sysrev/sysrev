package co.insilica.sysrev
package api

import co.insilica.apistack.ApiStack
import co.insilica.sysrev.indexing.{QueryEnv, DocIndex}
import DocIndex._
import Types._
import QueryEnv._

import org.scalatra._

import play.api.libs.iteratee.Iteratee

class SysrevServlet extends ApiStack with FutureSupport {

  implicit val executor = scala.concurrent.ExecutionContext.Implicits.global

  get("/") {
    "up"
  }

  get("/ranking") {
    indexing.sysrevImporter.select[SysRev]() flatMap (_ |>>> Iteratee.takeUpTo(100))
  }
}
