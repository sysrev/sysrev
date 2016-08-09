package co.insilica
package sysrev

import co.insilica.dataProvider.config.ConfigFileHandler
import org.scalatest._


class ImporterTestSpec extends AsyncFlatSpec with Matchers {

  implicit object default extends SysrevConfig{
    val fileHandler = new ConfigFileHandler[Config]{
      override def customFileName: String = ".insilica/sysrev/config_test.json"
    }
  }

  // "The systematic review importer"
  ignore should "import all data into mongo" in {
    Importer.importData("sysrev").map{ wr =>
      wr should not be None
      wr.get.forall(_.ok) should be (true)
    }
  }
}