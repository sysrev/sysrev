package co.insilica.sysrev.csvimporter

import org.scalatest._


class CsvImportTestSpec extends FlatSpec with Matchers{
  "Articles" should "be imported from file" in {
    val firstRow = CsvImport.getArticlesFromFile.take(1).head
    info("Read first row")
    info(firstRow.toString())
    firstRow.author.length should be > (5)
  }
}
