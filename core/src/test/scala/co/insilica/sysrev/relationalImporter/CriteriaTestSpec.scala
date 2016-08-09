package co.insilica
package sysrev
package relationalImporter

import dataProvider.TaskFutureOps._
import relationalImporter.Types._
import doobie.imports._

import scala.concurrent.Future
import scalaz._
import Scalaz._

import org.scalatest._


class CriteriaTestSpec extends AsyncFlatSpec with Matchers {
  import TestConfig._


  // "Known criteria"
  ignore should "be inserted" in {
    val r : Future[List[CriteriaId]] = Criteria.knownCriteria.map{ c =>
      Queries.insertCriteria(c).transact(tx).runFuture
    }.sequenceU

    r.map(_.length should be (7))
  }
}
