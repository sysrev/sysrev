package co.insilica.sysrev

import co.insilica.dataProvider.config.ConfigFileHandler


object TestConfig extends SysrevConfig{
  lazy val fileHandler = new ConfigFileHandler[Config]{
    override def customFileName: String = ".insilica/sysrev/config_test.json"
  }
}
