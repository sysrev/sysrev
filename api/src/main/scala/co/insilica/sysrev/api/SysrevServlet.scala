package co.insilica.sysrev.api

import co.insilica.apistack.ApiStack
import co.insilica.sysrev.indexing.DocIndex
import DocIndex._

import org.scalatra._

import play.api.libs.iteratee.Iteratee

class SysrevServlet extends ApiStack with FutureSupport {

  implicit val executor = scala.concurrent.ExecutionContext.Implicits.global

  get("/") {
    "up"
  }

  get("/ranking") {
    DocIndex.sysrevImporter.select() flatMap (_ |>>> Iteratee.takeUpTo(100))
  }
}
