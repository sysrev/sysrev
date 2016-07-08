package co.insilica.sysrev.relationalImporter

import org.scalatest._
import co.insilica.sysrev.Implicits._

class RelationalImporterTestSpec extends AsyncFlatSpec with Matchers {

  "All keywords" should "be imported to pg" in {
    RelationalImporter.allKeywords.map(_ should be > (100))
  }

  // TODO: improve this test. should store mongo id so we can test if articles have already been inserted.
  "All articles" should "be imported to pg" in {
    RelationalImporter.all.map(_ => true should be (true))
  }
}
