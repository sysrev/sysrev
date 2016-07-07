package co.insilica
package sysrev
package indexing

import org.scalatest._
import play.api.libs.iteratee.Iteratee
import DocIndex._

class DocIndexTestSpec extends AsyncFlatSpec with Matchers {


  "The doc importer" should "read out documents" in {
    DocIndex.sysrevImporter.select() flatMap (_ |>>> Iteratee.takeUpTo(5)) map { docs =>
      docs.length should equal (5)
    }
  }
}
