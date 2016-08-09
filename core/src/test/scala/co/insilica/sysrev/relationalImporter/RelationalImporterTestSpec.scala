package co.insilica.sysrev
package relationalImporter

import org.scalatest._

class RelationalImporterTestSpec extends AsyncFlatSpec with Matchers {
  import TestConfig._

  // "All keywords"
  ignore should "be imported to pg" in {
    RelationalImporter.allKeywords.map(_ should be > (100))
  }

  // TODO: improve this test. should store mongo id so we can test if articles have already been inserted.

  // "All articles"
  ignore should "be imported to pg" in {
    RelationalImporter.all.map(_ => true should be (true))
  }


}
