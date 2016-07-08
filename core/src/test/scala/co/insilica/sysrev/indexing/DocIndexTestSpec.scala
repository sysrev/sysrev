package co.insilica
package sysrev
package indexing

import org.scalatest._
import play.api.libs.iteratee.Iteratee
import DocIndex._
import QueryEnv._
import co.insilica.sysrev.Types._

class DocIndexTestSpec extends AsyncFlatSpec with Matchers {
  "The doc importer" should "read out documents" in {
    sysrevImporter.select[SysRev]() flatMap (_ |>>> Iteratee.takeUpTo(5)) map { docs =>
      docs.length should equal (5)
    }
  }
}
