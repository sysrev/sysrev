package co.insilica
package sysrev

import org.scalatest._


class ImporterTestSpec extends AsyncFlatSpec with Matchers {

  // "The systematic review importer"
  ignore should "import all data into mongo" in {
    Importer.importData.map{ wr =>
      wr should not be None
      wr.get.forall(_.ok) should be (true)
    }
  }
}